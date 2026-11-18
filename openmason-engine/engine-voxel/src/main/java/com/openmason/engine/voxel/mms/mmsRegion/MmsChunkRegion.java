package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.util.EngineProperties;
import com.openmason.engine.vram.VramArenaPolicy;
import com.openmason.engine.vram.VramPlans;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsSharedQuadIndexBuffer;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.mms.mmsCore.MmsStagingRing;
import com.openmason.engine.voxel.mms.mmsCore.MmsUploadBufferPool;
import org.lwjgl.opengl.ARBSparseBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;

import org.joml.Vector3f;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * One render region's shared GPU storage: a vertex arena (the region
 * format's stride), an index arena (u16 or u32; none for pulled formats, which
 * share the quad EBO), and ONE VAO covering both — so a whole region draws
 * with one VAO bind and one {@code glMultiDrawElementsBaseVertex} (or one GPU
 * cull + {@code glMultiDrawElementsIndirect}) instead of a bind + draw per mesh.
 *
 * <h2>Cubic regions (3D, 2026-11-17)</h2>
 * A region created with an {@link MmsRegionKey} covers 8x8x8 cubes = 256^3
 * blocks; its origin is the key's int64 block corner, every mesh in it is
 * REGION-LOCAL on all three axes (the packed formats address 0..256 per axis),
 * and the per-region origin the shader sees ({@code aOrigin}) is that int64
 * corner made camera-relative by {@link com.openmason.engine.rendering.RenderOrigin}
 * (exact long subtraction, re-baked on every origin step). Cull bounds are
 * region-local too ({@link #uploadLocal}); the culler adds
 * {@link #renderOrigin(Vector3f)}. Native meshes ({@code cv_gpu_mesh}) upload
 * straight from their {@link MemorySegment}s into the staging ring / arena.
 *
 * <p>Regions created without a key are the column-world path (deprecated, kept
 * until the game drops it): the first upload fixes the origin and the legacy
 * {@code upload(...)} overloads take world-space bounds, converted to
 * region-local on the way in.
 *
 * <p>Chunk meshes are sub-allocated via {@link MmsArenaAllocator}. Two
 * storage modes, chosen by the plan's {@code grow} directive:
 * <ul>
 *   <li><b>Copy</b> (default): when an arena runs out, a fresh larger GL
 *       buffer is created, live segments are packed into it with GPU-side
 *       {@code glCopyBufferSubData}, and the VAO is re-pointed. Segment
 *       offsets update in place, so handles stay valid across compaction.
 *   <li><b>Sparse</b> ({@code grow sparse}, needs ARB_sparse_buffer): the
 *       buffer reserves cheap virtual address space once and commits physical
 *       pages on demand under each uploaded mesh — growth is a capacity
 *       extension with <i>no copy, no spike, no VAO re-point, and offsets
 *       that never move</i>; the trim sweep decommits any page no live
 *       segment touches (copy-free, fragmentation-immune). Falls back to
 *       copy mode when the extension is missing or
 *       {@code -Dopenmason.sparse=off} is set.
 * </ul>
 *
 * <p>All methods are GL-thread confined. Uploads and copies go through the
 * {@code GL_COPY_READ/WRITE_BUFFER} targets so the VAO's recorded element
 * binding and the global vertex-array state are never disturbed.
 */
public final class MmsChunkRegion {

    /** Region span: 8 cubes per axis (cubic path; 8x8 chunk columns on the deprecated column path). */
    public static final int REGION_SHIFT = 3;
    public static final int REGION_SPAN = 1 << REGION_SHIFT;

    /** Bytes per mesh entry in the GPU-cull metadata SSBO (2×vec4 + uvec4, std430). */
    static final int GPU_META_STRIDE = 48;

    private final double growthFactor;
    private final double growthReserve;
    private final int alignElements;
    private final double trimFraction;
    private final int initialVertexCapacity;
    private final int initialIndexCapacity;
    private final MmsArenaAllocator vertexAlloc;
    private final MmsArenaAllocator indexAlloc;

    // Sparse mode (null/0 when running the classic copy path).
    /** Packed vertex layout every mesh in this region must use (fixed at creation). */
    private final MmsVertexFormat format;
    private final int vertexStride;
    /** Vertex pulling: no index arena (shared EBO), quads read via a buffer texture. */
    private final boolean pulled;
    private int quadTextureId;
    /** 3D region coordinates (cubic path), or null on the column path. */
    private final MmsRegionKey key;
    /** Bytes per index-arena element: 2 (GL_UNSIGNED_SHORT) or 4 (GL_UNSIGNED_INT). */
    private final int indexBytes;
    /** World origin shared by every mesh in the region: exact int64 block corner on the cubic path. */
    private long originBlockX, originBlockY, originBlockZ;
    /** The same origin as doubles (column path: the first upload's mesh origin, possibly fractional). */
    private double originX, originY, originZ;
    private boolean originSet;
    /** Sum of live handles' draw commands (GPU-cull metadata records). */
    private int liveCommands;
    private int originBufferId;
    private final boolean sparse;
    private final long pageSize;
    private final BitSet vertexPages;
    private final BitSet indexPages;
    private long vertexVirtualBytes;
    private long indexVirtualBytes;
    /** Sparse trim scheduled behind a fence, per arena (0 = vertex, 1 = index); see sweepDecommit. */
    private final long[] pendingDecommitFence = new long[2];
    private final BitSet[] pendingDecommit = new BitSet[2];

    private int vertexBufferId;
    private int indexBufferId;
    private int vaoId;
    private long trackedBytes;
    private boolean deleted;

    /** Live handles in metadata order (swap-remove; handle stores its index). */
    private final List<MmsRegionMeshHandle> liveHandles = new ArrayList<>();

    // Where this pass's cull wrote the region's commands (MmsIndirectRing).
    int gpuCmdBufferId;
    long gpuCmdOffsetBytes;

    // Hi-Z two-phase state (MmsGpuCuller.beginEarlyPass/beginLatePass). The visibility
    // history is one u32 per draw command, GPU-written by cull_late and read by the next
    // frame's cull_early; it describes the live list only while meshListVersion is unchanged.
    /** Bumped on every live-list change (upload / free): command indices may have moved. */
    int meshListVersion;
    /** The meshListVersion the history buffer describes; -1 = no history. */
    int hizHistoryVersion = -1;
    int hizVisBufferId;
    int hizVisCapacity;
    /** What this frame's phase 1 did with the region (epoch, rule, list version). */
    long hizEarlyEpoch = -1;
    int hizEarlyRule;
    int hizEarlyVersion;

    /** Per-draw-cycle member bucket, managed by the region renderer. */
    private final List<MmsRegionMeshHandle> cycleMembers = new ArrayList<>();
    private int cycleStamp = Integer.MIN_VALUE;

    public MmsChunkRegion() {
        this(VramPlans.arena(VramPlans.POOL_CHUNK_MESH));
    }

    /**
     * Creates a region sized and grown by a VRAM-plan arena policy (the CEARL
     * plan's pool arena, or the builtin defaults). Byte sizes convert to
     * element units here — the vertex arena divides by the 40-byte stride,
     * the index arena by 2.
     */
    public MmsChunkRegion(VramArenaPolicy policy) {
        this(MmsVertexFormat.active(), policy);
    }

    /** Creates a region holding meshes of an explicit format (stamp regions use {@code stampFormat()}). */
    public MmsChunkRegion(MmsVertexFormat format, VramArenaPolicy policy) {
        this(elementCount(policy.vertexInitialBytes(), format.stride()),
            format.pulled() ? 0 : elementCount(policy.indexInitialBytes(), Short.BYTES), policy, format,
            null, Short.BYTES);
    }

    /**
     * A cubic (3D) region: 8x8x8 cubes at {@code key}, region-local meshes,
     * u16 indices (pulled formats have none).
     */
    public MmsChunkRegion(MmsRegionKey key, MmsVertexFormat format, VramArenaPolicy policy) {
        this(key, format, policy, Short.BYTES);
    }

    /**
     * A cubic (3D) region with an explicit index width: {@code indexBytes} 4
     * for meshes carrying u32 indices (the native stamp meshes,
     * {@code cv_gpu_mesh.stamp_indices}), 2 for u16. Ignored for pulled formats.
     */
    public MmsChunkRegion(MmsRegionKey key, MmsVertexFormat format, VramArenaPolicy policy, int indexBytes) {
        this(elementCount(policy.vertexInitialBytes(), format.stride()),
            format.pulled() ? 0 : elementCount(policy.indexInitialBytes(), checkIndexBytes(indexBytes)),
            policy, format, java.util.Objects.requireNonNull(key, "key"), indexBytes);
    }

    private static int checkIndexBytes(int indexBytes) {
        if (indexBytes != Short.BYTES && indexBytes != Integer.BYTES) {
            throw new IllegalArgumentException("index width must be 2 or 4 bytes, got " + indexBytes);
        }
        return indexBytes;
    }

    /**
     * Creates a region with custom initial arena capacities (element units).
     * Callers whose regions hold many more meshes than the 8×8-column default
     * (e.g. 16×16-column LOD regions) start larger to avoid several
     * grow-and-compact cycles during the initial fill. Growth follows the
     * builtin default policy.
     */
    public MmsChunkRegion(int initialVertexCapacity, int initialIndexCapacity) {
        this(initialVertexCapacity, initialIndexCapacity, VramPlans.defaultArena(),
            MmsVertexFormat.active(), null, Short.BYTES);
    }

    private MmsChunkRegion(int initialVertexCapacity, int initialIndexCapacity,
                           VramArenaPolicy policy, MmsVertexFormat format,
                           MmsRegionKey key, int indexBytes) {
        this.key = key;
        this.indexBytes = format.pulled() ? Short.BYTES : checkIndexBytes(indexBytes);
        this.format = format;
        this.vertexStride = format.stride();
        this.pulled = format.pulled();
        if (pulled) {
            initialIndexCapacity = 0;
        }
        this.growthFactor = policy.growthFactor();
        this.growthReserve = policy.growthReserve();
        // Pulled arenas must stay quad-aligned (one quad = 4 "vertex" elements
        // = one RGBA32UI texel): never let a plan's align drop below 4.
        this.alignElements = pulled ? Math.max(4, policy.alignElements()) : policy.alignElements();
        this.trimFraction = policy.trimFraction();
        this.initialVertexCapacity = initialVertexCapacity;
        this.initialIndexCapacity = initialIndexCapacity;
        this.vertexAlloc = new MmsArenaAllocator(initialVertexCapacity);
        this.indexAlloc = pulled ? null : new MmsArenaAllocator(initialIndexCapacity);

        this.sparse = policy.sparseGrowth() && sparseSupported();
        if (sparse) {
            long queried = GL11.glGetInteger(ARBSparseBuffer.GL_SPARSE_BUFFER_PAGE_SIZE_ARB);
            this.pageSize = queried > 0 ? queried : 65536;
            this.vertexPages = new BitSet();
            this.indexPages = new BitSet();
            this.vertexVirtualBytes = virtualBytes(
                (long) initialVertexCapacity * vertexStride, pageSize);
            this.indexVirtualBytes = pulled ? 0 : virtualBytes(
                (long) initialIndexCapacity * this.indexBytes, pageSize);
            this.vertexBufferId = createSparseBuffer(vertexVirtualBytes);
            this.indexBufferId = pulled ? MmsSharedQuadIndexBuffer.id() : createSparseBuffer(indexVirtualBytes);
            // Physical pages commit on demand under the first uploads — an
            // empty sparse region costs (almost) nothing.
            trackedBytes = 0;
        } else {
            this.pageSize = 0;
            this.vertexPages = null;
            this.indexPages = null;
            this.vertexBufferId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, vertexBufferId);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
                (long) initialVertexCapacity * vertexStride,
                GL15.GL_STATIC_DRAW);
            if (pulled) {
                this.indexBufferId = MmsSharedQuadIndexBuffer.id();
            } else {
                this.indexBufferId = GL15.glGenBuffers();
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, indexBufferId);
                GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
                    (long) initialIndexCapacity * this.indexBytes, GL15.GL_STATIC_DRAW);
            }
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            trackedBytes = (long) initialVertexCapacity * vertexStride
                + (long) initialIndexCapacity * this.indexBytes;
        }

        if (pulled) {
            this.quadTextureId = GL11.glGenTextures();
            attachQuadTexture();
        }
        if (key != null) {
            // Cubic region: the origin is the key's int64 corner, known up front.
            this.originBlockX = key.minBlockX();
            this.originBlockY = key.minBlockY();
            this.originBlockZ = key.minBlockZ();
            this.originX = originBlockX;
            this.originY = originBlockY;
            this.originZ = originBlockZ;
            this.originSet = true;
            this.originBufferId = format.createOriginBuffer(originBlockX, originBlockY, originBlockZ);
        }
        this.vaoId = GL30.glGenVertexArrays();
        rebuildVao();
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.CHUNK_MESH, trackedBytes);
    }

    // ─── Sparse-mode support ──────────────────────────────────────────────

    private static Boolean sparseCapable;

    /** One-time capability + kill-switch check (only reached when a plan asks). */
    private static boolean sparseSupported() {
        if (sparseCapable == null) {
            boolean off = EngineProperties.isOff("sparse");
            boolean capable;
            try {
                GL.getCapabilities();
                capable = GL.getCapabilities().GL_ARB_sparse_buffer
                    && (GL.getCapabilities().OpenGL44
                        || GL.getCapabilities().GL_ARB_buffer_storage);
            } catch (IllegalStateException e) {
                return false; // No context on this thread — stay undecided.
            }
            sparseCapable = !off && capable;
            System.out.println("[MmsChunkRegion] Sparse arenas "
                + (sparseCapable ? "ENABLED (page commitment growth)"
                    : "unavailable (" + (off ? "-D" + EngineProperties.name("sparse") + "=off"
                        : "no ARB_sparse_buffer") + ") — copy growth"));
        }
        return sparseCapable;
    }

    /** Virtual reservation for one arena: generous, page-aligned, still bounded. */
    static long virtualBytes(long initialBytes, long pageSize) {
        long virtual = Math.clamp(initialBytes * 32, 16L << 20, 128L << 20);
        virtual = Math.max(virtual, initialBytes * 2);
        return alignUp(virtual, pageSize);
    }

    static long alignUp(long value, long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private int createSparseBuffer(long virtualCapacityBytes) {
        int id = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, id);
        GL44.glBufferStorage(GL31.GL_COPY_WRITE_BUFFER, virtualCapacityBytes,
            GL44.GL_DYNAMIC_STORAGE_BIT | ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        return id;
    }

    /** Commits any uncommitted pages covering [fromByte, toByte) — batched runs. */
    private void ensureCommitted(boolean vertex, long fromByte, long toByte) {
        BitSet pages = vertex ? vertexPages : indexPages;
        int buffer = vertex ? vertexBufferId : indexBufferId;
        int firstPage = (int) (fromByte / pageSize);
        int endPage = (int) ((toByte + pageSize - 1) / pageSize);
        // A scheduled page that gets reused leaves the schedule: its new mesh
        // is drawn after the pending fence, so if it dies again it must wait
        // behind a fresh fence (sweepDecommit), not the stale one.
        BitSet pending = pendingDecommit[vertex ? 0 : 1];
        if (pending != null) {
            pending.clear(firstPage, endPage);
        }
        boolean bound = false;
        int p = firstPage;
        while (p < endPage) {
            if (pages.get(p)) {
                p++;
                continue;
            }
            int runStart = p;
            while (p < endPage && !pages.get(p)) {
                p++;
            }
            if (!bound) {
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
                bound = true;
            }
            long bytes = (long) (p - runStart) * pageSize;
            ARBSparseBuffer.glBufferPageCommitmentARB(GL31.GL_COPY_WRITE_BUFFER,
                (long) runStart * pageSize, bytes, true);
            pages.set(runStart, p);
            trackedBytes += bytes;
            GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.CHUNK_MESH, bytes);
        }
        if (bound) {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        }
    }

    /**
     * Column path: uploads one LEGACY40 chunk mesh. The six bound parameters
     * are the mesh's WORLD-space AABB (converted to region-local here).
     *
     * @param vertexBytes interleaved {@link MmsBufferLayout} vertex bytes
     * @param indexBytes  u16 index bytes
     * @return a live handle whose segments the caller must eventually close
     * @deprecated column-world path; cubic regions use {@link #uploadLocal}.
     */
    @Deprecated
    public MmsRegionMeshHandle upload(byte[] vertexBytes, byte[] indexBytes,
                                      int vertexCount, int indexCount,
                                      float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        return upload(vertexBytes, indexBytes, vertexCount, indexCount,
            MmsVertexFormat.LEGACY40, 0f, 0f, 0f, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Uploads a packed Java-built mesh: its format must match the region's and
     * its origin the region's (cubic regions: the key's corner, i.e. the mesh
     * was built region-local). Bounds are WORLD-space, converted to
     * region-local in double here.
     */
    public MmsRegionMeshHandle upload(MmsMeshData mesh, float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        return upload(mesh.getPackedVertexData(), mesh.getPackedIndexData(),
            mesh.getVertexCount(), mesh.getIndexCount(), mesh.getFormat(),
            mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(),
            minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * @param meshFormat layout of {@code vertexBytes} — must equal this region's format
     * @param originX    world origin the mesh positions are relative to (local formats);
     *                   on the column path the first upload fixes the region origin, later
     *                   ones (and every upload into a cubic region) must agree
     * @param minX       WORLD-space bounds (converted to region-local in double)
     */
    public MmsRegionMeshHandle upload(byte[] vertexBytes, byte[] indexBytes,
                                      int vertexCount, int indexCount,
                                      MmsVertexFormat meshFormat,
                                      float originX, float originY, float originZ,
                                      float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        ensureNotDeleted();
        if (meshFormat != format) {
            throw new IllegalArgumentException("Mesh format " + meshFormat
                + " cannot join a " + format + " region");
        }
        if (!pulled && this.indexBytes != Short.BYTES) {
            throw new IllegalArgumentException("byte[] meshes carry u16 indices; this region holds u"
                + (this.indexBytes * 8) + " (use uploadLocal)");
        }
        // Every format gets an origin buffer, local-position or not: it is also
        // the seam that rebases the region into render space (RenderOrigin).
        // LEGACY40 meshes report origin (0,0,0), which agrees across uploads.
        if (!originSet) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.originBlockX = (long) Math.floor(originX);
            this.originBlockY = (long) Math.floor(originY);
            this.originBlockZ = (long) Math.floor(originZ);
            this.originSet = true;
            this.originBufferId = format.createOriginBuffer(originX, originY, originZ);
            rebuildVao();
        } else if (originX != this.originX || originY != this.originY || originZ != this.originZ) {
            throw new IllegalArgumentException(String.format(
                "Mesh origin (%.1f,%.1f,%.1f) differs from region origin (%.1f,%.1f,%.1f)",
                originX, originY, originZ, this.originX, this.originY, this.originZ));
        }
        return place(MemorySegment.ofArray(vertexBytes), vertexCount,
            pulled ? null : MemorySegment.ofArray(indexBytes), indexCount,
            pulled ? vertexCount / 4 : 0,
            (float) (minX - this.originX), (float) (minY - this.originY), (float) (minZ - this.originZ),
            (float) (maxX - this.originX), (float) (maxY - this.originY), (float) (maxZ - this.originZ));
    }

    /**
     * Uploads a REGION-LOCAL mesh into a cubic region straight from (native or
     * heap) memory: the bytes go into the persistent staging ring (or one
     * {@code glBufferSubData} from the segment's address) with no Java heap
     * copy — the path for {@code cv_gpu_mesh} buffers handed out by the native
     * streamer. Positions in the bytes are relative to this region's origin;
     * so are the bounds. The bytes are consumed before this returns (memcpy into
     * the ring, or {@code glBufferSubData}, which copies), so the caller may
     * release the native mesh ({@code cv_stream_release_mesh}) right after.
     *
     * @param vertices    {@code vertexCount * format().stride()} bytes (pulled formats:
     *                    4 elements per 16-byte quad record)
     * @param vertexCount vertex-arena elements
     * @param indices     {@code indexCount * indexBytes()} bytes; ignored (may be null) for pulled formats
     * @param indexCount  indices (pulled formats: 6 per quad)
     */
    public MmsRegionMeshHandle uploadLocal(MemorySegment vertices, int vertexCount,
                                           MemorySegment indices, int indexCount,
                                           float minX, float minY, float minZ,
                                           float maxX, float maxY, float maxZ) {
        ensureNotDeleted();
        if (key == null) {
            throw new IllegalStateException("uploadLocal needs a cubic region (created with an MmsRegionKey)");
        }
        long vertexBytesNeeded = (long) vertexCount * vertexStride;
        if (vertices.byteSize() < vertexBytesNeeded) {
            throw new IllegalArgumentException("vertex segment holds " + vertices.byteSize()
                + " bytes, " + vertexCount + " elements need " + vertexBytesNeeded);
        }
        if (!pulled) {
            long indexBytesNeeded = (long) indexCount * indexBytes;
            if (indices == null || indices.byteSize() < indexBytesNeeded) {
                throw new IllegalArgumentException("index segment holds "
                    + (indices == null ? 0 : indices.byteSize()) + " bytes, " + indexCount
                    + " u" + (indexBytes * 8) + " indices need " + indexBytesNeeded);
            }
        } else if ((vertexCount & 3) != 0) {
            throw new IllegalArgumentException("pulled meshes upload whole quads (4 elements each), got "
                + vertexCount);
        }
        return place(vertices.asSlice(0, vertexBytesNeeded), vertexCount,
            pulled ? null : indices.asSlice(0, (long) indexCount * indexBytes), indexCount,
            pulled ? vertexCount / 4 : 0, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * {@link #uploadLocal} for a pulled format ({@code QUAD16} / {@code WATERQUAD16} /
     * {@code LODQUAD16}): {@code quadCount} 16-byte records, region-local bounds.
     */
    public MmsRegionMeshHandle uploadQuads(MemorySegment quads, int quadCount,
                                           float minX, float minY, float minZ,
                                           float maxX, float maxY, float maxZ) {
        if (!pulled) {
            throw new IllegalStateException(format + " is not a pulled quad format");
        }
        return uploadLocal(quads, quadCount * 4, null, quadCount * 6, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Allocates arena space, streams the bytes in and registers the handle. Bounds are region-local. */
    private MmsRegionMeshHandle place(MemorySegment vertexData, int vertexCount,
                                      MemorySegment indexData, int indexCount, int quadCount,
                                      float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        MmsArenaAllocator.Segment vertexSeg = allocOrGrow(vertexAlloc, vertexCount, true);
        MmsArenaAllocator.Segment indexSeg = null;
        if (!pulled) {
            try {
                indexSeg = allocOrGrow(indexAlloc, indexCount, false);
            } catch (RuntimeException e) {
                // Don't strand the vertex segment if the index arena can't grow.
                vertexAlloc.free(vertexSeg);
                throw e;
            }
        }

        if (sparse) {
            // Physical pages materialize exactly where meshes land.
            ensureCommitted(true,
                (long) vertexSeg.offset() * vertexStride,
                (long) (vertexSeg.offset() + vertexSeg.length()) * vertexStride);
            if (indexSeg != null) {
                ensureCommitted(false,
                    (long) indexSeg.offset() * indexBytes,
                    (long) (indexSeg.offset() + indexSeg.length()) * indexBytes);
            }
        }
        uploadBytes(vertexBufferId, (long) vertexSeg.offset() * vertexStride, vertexData);
        if (indexSeg != null) {
            uploadBytes(indexBufferId, (long) indexSeg.offset() * indexBytes, indexData);
        }

        MmsRegionMeshHandle handle = new MmsRegionMeshHandle(this, vertexSeg, indexSeg, indexCount,
            indexBytes, quadCount, minX, minY, minZ, maxX, maxY, maxZ);
        handle.liveIndex = liveHandles.size();
        liveHandles.add(handle);
        liveCommands += handle.commandCount();
        meshListVersion++;
        return handle;
    }

    /** Returns a handle's segments to the arenas (called via handle.close()). */
    void free(MmsRegionMeshHandle handle) {
        if (deleted) {
            return; // Region already torn down wholesale.
        }
        vertexAlloc.free(handle.vertexSegment);
        if (indexAlloc != null && handle.indexSegment != null) {
            indexAlloc.free(handle.indexSegment);
        }
        liveCommands -= handle.commandCount();
        meshListVersion++;
        MmsRegionMeshHandle last = liveHandles.removeLast();
        if (last != handle) {
            liveHandles.set(handle.liveIndex, last);
            last.liveIndex = handle.liveIndex;
        }
    }

    /** True when no live handles remain — the owner may delete the region. */
    public boolean isEmpty() {
        return liveHandles.isEmpty();
    }

    /** True once {@link #delete()} has released the GL resources. */
    public boolean isDeleted() {
        return deleted;
    }

    /** Binds the region VAO (vertex attributes + element buffer). */
    public void bind() {
        ensureNotDeleted();
        GL30.glBindVertexArray(vaoId);
        if (pulled) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + MmsQuadCodec.QUAD_TEXTURE_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, quadTextureId);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    /** (Re)points the quad buffer texture at the current vertex buffer (pulled regions). */
    private void attachQuadTexture() {
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, quadTextureId);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32UI, vertexBufferId);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
    }

    /** The packed vertex layout this region's arenas hold. */
    public MmsVertexFormat format() {
        return format;
    }

    /** Bytes per vertex in this region's vertex arena. */
    public int vertexStride() {
        return vertexStride;
    }

    /** Bytes per index-arena element (2 or 4; 2 for pulled formats, whose shared quad EBO is u16). */
    public int indexBytes() {
        return indexBytes;
    }

    /** GL element type of this region's draws: {@code GL_UNSIGNED_SHORT} or {@code GL_UNSIGNED_INT}. */
    public int indexType() {
        return indexBytes == Integer.BYTES ? GL11.GL_UNSIGNED_INT : GL11.GL_UNSIGNED_SHORT;
    }

    /** The region's 3D coordinates, or null for a column-path region. */
    public MmsRegionKey key() {
        return key;
    }

    /** Exact int64 block origin X (cubic regions: the key's corner). */
    public long originBlockX() {
        return originBlockX;
    }

    public long originBlockY() {
        return originBlockY;
    }

    public long originBlockZ() {
        return originBlockZ;
    }

    /** Region origin X as a float (column path compatibility; inexact past 2^24 — use {@link #originBlockX()}). */
    public float originX() {
        return (float) originX;
    }

    public float originY() {
        return (float) originY;
    }

    public float originZ() {
        return (float) originZ;
    }

    /**
     * The region origin made camera-relative ({@code origin - RenderOrigin}),
     * computed in long/double and rounded once to float — what the culler adds
     * to every region-local AABB and what the shader's {@code aOrigin.xyz}
     * holds (before the format's Y shift). Recompute after an origin step.
     */
    public Vector3f renderOrigin(Vector3f dest) {
        if (key != null) {
            return com.openmason.engine.rendering.RenderOrigin.relative(
                originBlockX, originBlockY, originBlockZ, dest);
        }
        return com.openmason.engine.rendering.RenderOrigin.relative(originX, originY, originZ, dest);
    }

    /** GL ids for the opt-in GL test (vertex arena, index arena, origin attribute buffer). */
    int vertexBufferIdForTest() {
        return vertexBufferId;
    }

    int indexBufferIdForTest() {
        return indexBufferId;
    }

    int originBufferIdForTest() {
        return originBufferId;
    }

    /** True when this region grows by sparse page commitment rather than copy. */
    public boolean isSparse() {
        return sparse;
    }

    /** Sparse page size in bytes (0 in copy mode). */
    public long pageSize() {
        return pageSize;
    }

    /** GPU bytes currently reserved by this region's buffers. */
    public long capacityBytes() {
        return trackedBytes;
    }

    // ─── Draw-cycle member bucket (used by the region renderer) ───────────

    /**
     * Returns this region's member bucket for the given draw cycle, clearing
     * it when the region is first touched in that cycle.
     */
    public List<MmsRegionMeshHandle> cycleMembers(int stamp) {
        if (cycleStamp != stamp) {
            cycleStamp = stamp;
            cycleMembers.clear();
        }
        return cycleMembers;
    }

    /** Whether this region was already touched in the given draw cycle. */
    public boolean touchedInCycle(int stamp) {
        return cycleStamp == stamp;
    }

    // ─── GPU-driven culling (used by MmsGpuCuller, GL 4.3+) ───────────────

    /**
     * Writes the live set's cull metadata ({@link #GPU_META_STRIDE} bytes per
     * draw command, in command order) into {@code dst}: region-local AABB
     * ({@code minb.xyz}, {@code maxb.xyz}), then {@code (indexCount,
     * firstIndex, baseVertex, flags)} with {@link MmsRegionMeshHandle#FLAG_ENABLED}
     * in bit 0. The culler uploads it to fresh fenced ring space every cull:
     * an in-place SSBO updated with {@code glBufferSubData} while the CPU ran
     * frames ahead let queued cull dispatches read the next frame's metadata
     * (swap-remove reorders it), and the commands they built drew stretched
     * triangles (NVIDIA 595, Blackwell).
     */
    void writeGpuCullMeta(ByteBuffer dst) {
        writeGpuCullMeta(dst, null);
    }

    /**
     * {@link #writeGpuCullMeta(ByteBuffer)} that also clears the enable bit of
     * every mesh whose WORLD-space AABB lies wholly outside the vertical box
     * {@code keepXZ = {minX, minZ, maxX, maxZ}} (column-path compatibility for
     * the old keep-box planes; null = no box). Computed in double.
     */
    void writeGpuCullMeta(ByteBuffer dst, float[] keepXZ) {
        ensureNotDeleted();
        for (MmsRegionMeshHandle h : liveHandles) {
            int flags = h.metaFlags();
            if (keepXZ != null && !insideKeepBox(h, keepXZ)) {
                flags &= ~MmsRegionMeshHandle.FLAG_ENABLED;
            }
            writeMeta(dst, h, flags);
        }
    }

    private boolean insideKeepBox(MmsRegionMeshHandle h, float[] keepXZ) {
        return originX + h.maxX >= keepXZ[0] && originZ + h.maxZ >= keepXZ[1]
            && originX + h.minX <= keepXZ[2] && originZ + h.minZ <= keepXZ[3];
    }

    /** One handle's MeshMeta records (one per draw command). Package-private for tests. */
    static void writeMeta(ByteBuffer dst, MmsRegionMeshHandle h, int flags) {
        for (int k = 0; k < h.commandCount(); k++) {
            dst.putFloat(h.minX).putFloat(h.minY).putFloat(h.minZ).putFloat(0f);
            dst.putFloat(h.maxX).putFloat(h.maxY).putFloat(h.maxZ).putFloat(0f);
            dst.putInt(h.commandIndexCount(k))
                .putInt(h.commandFirstIndex(k))
                .putInt(h.commandBaseVertex(k)).putInt(flags);
        }
    }

    // ─── CPU culling (GL 3.2 / macOS 4.1 path, same semantics as the kernel) ──

    private final Vector3f cpuOrigin = new Vector3f();

    /**
     * Appends every enabled live mesh whose region-local AABB (plus this
     * region's camera-relative origin) passes {@code renderPlanes} to
     * {@code batch} — the CPU twin of the GPU cull kernel, same plane test,
     * same enable semantics. Draw it with {@code bind()} +
     * {@code batch.draw(indexType())}.
     *
     * @param renderPlanes 6 render-space frustum planes (24 floats, JOML
     *                     {@code frustumPlane} convention), or null to skip the frustum test
     * @return commands appended
     */
    public int appendVisible(MmsMultiDrawBatch batch, float[] renderPlanes) {
        ensureNotDeleted();
        Vector3f o = renderOrigin(cpuOrigin);
        int added = 0;
        for (int i = 0; i < liveHandles.size(); i++) {
            MmsRegionMeshHandle h = liveHandles.get(i);
            if (!h.isEnabled()) {
                continue;
            }
            if (renderPlanes != null && !MmsGpuCuller.aabbVisible(renderPlanes, o.x, o.y, o.z,
                    h.minX, h.minY, h.minZ, h.maxX, h.maxY, h.maxZ)) {
                continue;
            }
            added += batch.add(h);
        }
        return added;
    }

    /** Sum of live meshes' index counts (6 per quad) — a page-independent size measure. */
    public long liveIndexCount() {
        long n = 0;
        for (MmsRegionMeshHandle h : liveHandles) {
            n += h.getIndexCount();
        }
        return n;
    }

    /** Command count a cull writes for this region (one per live mesh, more for split pulled meshes). */
    public int gpuCommandCount() {
        return liveCommands;
    }

    /** Live meshes in this region. */
    public int liveMeshCount() {
        return liveHandles.size();
    }

    // ─── Internals ────────────────────────────────────────────────────────

    private MmsArenaAllocator.Segment allocOrGrow(MmsArenaAllocator alloc, int length,
                                                  boolean vertex) {
        MmsArenaAllocator.Segment segment = alloc.alloc(length);
        if (segment != null) {
            return segment;
        }
        long grown = nextCapacity(alloc.used() + length, alloc.capacity(),
            growthFactor, growthReserve, alignElements);
        long elementBytes = vertex ? vertexStride : indexBytes;
        if (sparse) {
            // Sparse growth: pure bookkeeping — capacity extends in place,
            // offsets never move, pages commit under the upload that follows.
            // CRITICAL: extension does NOT compact, so fragmented free spans
            // are useless to this request — the TAIL EXTENSION alone must fit
            // it, or a fragmented arena throws on large meshes even with
            // plenty of total free space (live bug, 2026-08-19). Virtual
            // capacity is free; over-extending costs zero physical bytes.
            long target = sparseExtendTarget(grown, alloc.capacity(), length,
                growthReserve, alignElements);
            long virtualLimit = vertex ? vertexVirtualBytes : indexVirtualBytes;
            if (target * elementBytes <= virtualLimit) {
                alloc.extendTo(target);
            } else {
                resizeArena(alloc, grown, vertex); // compacts + doubles the reservation
            }
        } else {
            resizeArena(alloc, grown, vertex);
        }
        segment = alloc.alloc(length);
        if (segment == null) {
            // Belt and braces: compaction always yields one contiguous free
            // span of at least the reserve over the need.
            resizeArena(alloc, nextCapacity(alloc.used() + length, alloc.capacity(),
                growthFactor, growthReserve, alignElements), vertex);
            segment = alloc.alloc(length);
        }
        if (segment == null) {
            throw new IllegalStateException("Region arena grow failed to fit " + length + " elements");
        }
        return segment;
    }

    /**
     * The sparse-mode grow target: at least the normal geometric growth, and
     * always enough that the request (plus reserve) fits in the contiguous
     * tail extension regardless of how fragmented the existing spans are.
     */
    static long sparseExtendTarget(long grown, long capacity, long length,
                                   double reserve, int align) {
        long tailFit = capacity + length + (long) (length * reserve);
        long mask = align - 1L;
        return Math.max(grown, (tailFit + mask) & ~mask);
    }

    /**
     * The grown capacity for an arena that needs {@code needed} elements:
     * whichever is larger of needed-plus-reserve and capacity-times-factor,
     * rounded up to the alignment. With the default policy (1.75 / 25% / 4)
     * this is bit-identical to the pre-CEARL formula
     * {@code max(needed + (needed >> 2), capacity * 7 / 4)} — pinned by
     * {@code MmsChunkRegionGrowthTest}.
     */
    static long nextCapacity(long needed, long capacity, double factor, double reserve,
                             int align) {
        long grown = Math.max(needed + (long) (needed * reserve), (long) (capacity * factor));
        long mask = align - 1L;
        return (grown + mask) & ~mask;
    }

    /** Converts a policy's byte size to whole elements (at least 1024). */
    private static int elementCount(long bytes, int elementBytes) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1024, bytes / elementBytes));
    }

    /**
     * Shrinks one underused arena back toward its live contents — the
     * counterpart to growth, driven by the plan's {@code trim} directive.
     * Without it arenas ratchet to their session high-water capacity forever
     * (cross one dense area and the memory never comes back). At most one
     * arena is resized per call so the GPU-side repack cost stays a
     * once-per-frame background expense; callers invoke it from their
     * per-frame prune pass. Returns true when a resize happened. GL thread.
     */
    public boolean maybeTrim() {
        if (deleted) {
            return false;
        }
        boolean acted;
        if (sparse) {
            // Copy-free: decommit every committed page no live segment
            // touches. Cheap enough to sweep both arenas each call.
            acted = sweepDecommit(true) | (!pulled && sweepDecommit(false));
        } else {
            acted = false;
            long vertexTarget = trimTarget(vertexAlloc.used(), vertexAlloc.capacity(),
                initialVertexCapacity, trimFraction, growthReserve, alignElements);
            if (vertexTarget > 0) {
                resizeArena(vertexAlloc, vertexTarget, true);
                acted = true;
            } else if (!pulled) {
                long indexTarget = trimTarget(indexAlloc.used(), indexAlloc.capacity(),
                    initialIndexCapacity, trimFraction, growthReserve, alignElements);
                if (indexTarget > 0) {
                    resizeArena(indexAlloc, indexTarget, false);
                    acted = true;
                }
            }
        }
        return acted;
    }

    /**
     * Sparse trim: decommits committed pages that no live segment touches.
     * Gated like copy-trim (usage under {@code trimFraction} of committed,
     * recover at least a third — except a fully empty arena always drops to
     * zero physical). Fragmentation-immune: a page frees the moment nothing
     * lives on it, wherever it sits.
     *
     * <p>Two-phase, fenced. A page just went dead because a mesh on it was
     * freed, but draws queued in earlier frames may still reference that mesh
     * and the GPU runs frames behind the CPU. Reading an uncommitted sparse
     * page returns undefined bytes (ARB_sparse_buffer), and the spec does not
     * order a commitment change against in-flight commands — decommitting
     * immediately drew one frame of garbage quads (sky-wall flashes) whenever
     * meshes were freed in bulk. So a sweep only <em>schedules</em> the dead
     * set behind a fence; a later sweep decommits it once the fence has
     * signalled, intersected with what is still dead by then (pages
     * re-occupied in the meantime stay committed).
     */
    private boolean sweepDecommit(boolean vertex) {
        if (trimFraction <= 0) {
            return false;
        }
        int arena = vertex ? 0 : 1;
        BitSet pages = vertex ? vertexPages : indexPages;
        if (pendingDecommitFence[arena] != 0) {
            int status = GL32.glClientWaitSync(pendingDecommitFence[arena], 0, 0L);
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
                return false; // GPU still working through frames that may read these pages
            }
            GL32.glDeleteSync(pendingDecommitFence[arena]);
            pendingDecommitFence[arena] = 0;
            BitSet dead = deadPages(vertex);
            dead.and(pendingDecommit[arena]);
            pendingDecommit[arena] = null;
            return decommit(vertex, dead);
        }
        MmsArenaAllocator alloc = vertex ? vertexAlloc : indexAlloc;
        long elementBytes = vertex ? vertexStride : indexBytes;
        long committed = (long) pages.cardinality() * pageSize;
        if (committed == 0) {
            return false;
        }
        long usedBytes = alloc.used() * elementBytes;
        if (usedBytes > (long) (committed * trimFraction)) {
            return false;
        }
        BitSet dead = deadPages(vertex);
        long deadBytes = (long) dead.cardinality() * pageSize;
        if (deadBytes == 0 || (usedBytes > 0 && deadBytes * 3 < committed)) {
            return false;
        }
        // Everything queued so far — including the last draws of the freed
        // meshes — precedes this fence.
        pendingDecommit[arena] = dead;
        pendingDecommitFence[arena] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        return false;
    }

    /** Committed pages of one arena that no live segment touches. */
    private BitSet deadPages(boolean vertex) {
        BitSet pages = vertex ? vertexPages : indexPages;
        MmsArenaAllocator alloc = vertex ? vertexAlloc : indexAlloc;
        long elementBytes = vertex ? vertexStride : indexBytes;
        BitSet live = new BitSet();
        alloc.forEachLive((offset, length) -> {
            int from = (int) ((long) offset * elementBytes / pageSize);
            int to = (int) ((((long) offset + length) * elementBytes - 1) / pageSize);
            live.set(from, to + 1);
        });
        BitSet dead = (BitSet) pages.clone();
        dead.andNot(live);
        return dead;
    }

    /** Releases the physical backing of {@code dead} pages. Returns true when any were freed. */
    private boolean decommit(boolean vertex, BitSet dead) {
        if (dead.isEmpty()) {
            return false;
        }
        BitSet pages = vertex ? vertexPages : indexPages;
        long deadBytes = (long) dead.cardinality() * pageSize;
        int buffer = vertex ? vertexBufferId : indexBufferId;
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        int p = dead.nextSetBit(0);
        while (p >= 0) {
            int runEnd = dead.nextClearBit(p);
            ARBSparseBuffer.glBufferPageCommitmentARB(GL31.GL_COPY_WRITE_BUFFER,
                (long) p * pageSize, (long) (runEnd - p) * pageSize, false);
            pages.clear(p, runEnd);
            p = dead.nextSetBit(runEnd);
        }
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        trackedBytes -= deadBytes;
        GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.CHUNK_MESH, deadBytes);
        return true;
    }

    /** Drops a scheduled decommit (its pages are about to be replaced or deleted). */
    private void cancelPendingDecommit(int arena) {
        if (pendingDecommitFence[arena] != 0) {
            GL32.glDeleteSync(pendingDecommitFence[arena]);
            pendingDecommitFence[arena] = 0;
        }
        pendingDecommit[arena] = null;
    }

    /**
     * The trimmed capacity for an arena, or -1 when no trim should happen.
     * Trim fires only when live elements sit under {@code trimFraction} of
     * capacity AND the arena is above its initial size; the target keeps the
     * normal growth reserve over the live bytes and never drops below the
     * initial capacity. Hysteresis: a trim must return at least a third of
     * the arena, so trim and growth (factor ≥ 1.5) can never oscillate.
     */
    static long trimTarget(long used, long capacity, long initialCapacity,
                           double trimFraction, double reserve, int align) {
        if (trimFraction <= 0 || capacity <= initialCapacity) {
            return -1;
        }
        if (used > (long) (capacity * trimFraction)) {
            return -1;
        }
        long mask = align - 1L;
        long target = Math.max(initialCapacity,
            (used + (long) (used * reserve) + mask) & ~mask);
        if (target * 3L > capacity * 2L) {
            return -1;
        }
        return target;
    }

    /**
     * Resizes one arena (growth or trim): fresh GL buffer at the new capacity, GPU-side copies
     * packing every live segment to the tail, old buffer deleted, VAO
     * re-pointed at the new buffer.
     */
    private static final boolean ARENA_DEBUG = EngineProperties.flag("arena.debug");

    private void resizeArena(MmsArenaAllocator alloc, long newCapacity, boolean vertex) {
        int elementBytes = vertex ? vertexStride : indexBytes;
        if (ARENA_DEBUG) {
            System.out.println("[arena] " + format + (vertex ? " vertex" : " index")
                + (newCapacity < alloc.capacity() ? " TRIM " : " GROW ")
                + alloc.capacity() + " -> " + newCapacity + " (used " + alloc.used()
                + ", live " + liveHandles.size() + ", sparse " + sparse + ")");
        }
        long newBytes = newCapacity * elementBytes;
        // Captured BEFORE compaction mutates the allocator's capacity.
        long oldBytes = sparse
            ? (long) (vertex ? vertexPages : indexPages).cardinality() * pageSize
            : alloc.capacity() * elementBytes;
        int oldBuffer = vertex ? vertexBufferId : indexBufferId;

        List<MmsArenaAllocator.Move> moves = alloc.compactTo(newCapacity);

        int newBuffer;
        if (sparse) {
            // Rare path: the virtual reservation itself ran out. Reserve a
            // bigger one, commit the packed range wholesale, copy across.
            BitSet pages = vertex ? vertexPages : indexPages;
            long newVirtual = alignUp(
                Math.max((vertex ? vertexVirtualBytes : indexVirtualBytes) * 2, newBytes * 2),
                pageSize);
            newBuffer = createSparseBuffer(newVirtual);
            long commitBytes = alignUp(newBytes, pageSize);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newBuffer);
            ARBSparseBuffer.glBufferPageCommitmentARB(GL31.GL_COPY_WRITE_BUFFER,
                0, commitBytes, true);
            cancelPendingDecommit(vertex ? 0 : 1); // old buffer and its page map go away
            pages.clear();
            pages.set(0, (int) (commitBytes / pageSize));
            if (vertex) {
                vertexVirtualBytes = newVirtual;
            } else {
                indexVirtualBytes = newVirtual;
            }
            newBytes = commitBytes;
        } else {
            newBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newBuffer);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, newBytes, GL15.GL_STATIC_DRAW);
        }
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, oldBuffer);
        for (MmsArenaAllocator.Move move : moves) {
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                (long) move.from() * elementBytes,
                (long) move.to() * elementBytes,
                (long) move.length() * elementBytes);
        }
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glDeleteBuffers(oldBuffer);

        if (vertex) {
            vertexBufferId = newBuffer;
            if (pulled) {
                attachQuadTexture();
            }
        } else {
            indexBufferId = newBuffer;
        }
        rebuildVao();

        long delta = newBytes - oldBytes;
        // track() ignores non-positive deltas, so a trim must go through
        // untrack() or the accounting would silently ratchet upward.
        if (delta > 0) {
            GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.CHUNK_MESH, delta);
        } else if (delta < 0) {
            GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.CHUNK_MESH, -delta);
        }
        trackedBytes += delta;
    }

    /**
     * (Re)records the VAO: vertex attributes over the vertex buffer and the
     * element binding. Element unbind must happen AFTER the VAO unbind or it
     * would be recorded into the VAO.
     */
    private void rebuildVao() {
        GL30.glBindVertexArray(vaoId);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBufferId);
        format.setupVertexAttributes();
        if (originBufferId != 0) {
            format.setupOriginAttribute(originBufferId);
        }
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /**
     * Streams {@code data} (heap or native) into {@code bufferId} at
     * {@code offsetBytes}. Preferred path: the persistent-mapped staging ring
     * (GL 4.4) — one memcpy into mapped memory, then a GPU-side copy, no
     * driver staging allocation. Fallback: {@code glBufferSubData} straight
     * from a native segment's address, or via a pooled direct buffer for heap
     * data.
     */
    private void uploadBytes(int bufferId, long offsetBytes, MemorySegment data) {
        if (data.byteSize() == 0) {
            return;
        }
        MmsStagingRing ring = MmsStagingRing.sharedInstance();
        if (ring != null && ring.upload(bufferId, offsetBytes, data)) {
            return;
        }
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, bufferId);
        if (data.isNative()) {
            GL15.nglBufferSubData(GL31.GL_COPY_WRITE_BUFFER, offsetBytes, data.byteSize(), data.address());
        } else {
            int len = Math.toIntExact(data.byteSize());
            ByteBuffer staging = MmsUploadBufferPool.acquire(len);
            MemorySegment.ofBuffer(staging).copyFrom(data);
            staging.limit(len);
            GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, offsetBytes, staging);
        }
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    private void ensureNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("Region has been deleted");
        }
    }

    /** Deletes the region's GL resources. Outstanding handles become inert. */
    public void delete() {
        if (deleted) {
            return;
        }
        deleted = true;
        GL30.glDeleteVertexArrays(vaoId);
        if (originBufferId != 0) {
            com.openmason.engine.rendering.RenderOrigin.releaseOriginBuffer(originBufferId);
            GL15.glDeleteBuffers(originBufferId);
            originBufferId = 0;
        }
        GL15.glDeleteBuffers(vertexBufferId);
        if (!pulled) {
            GL15.glDeleteBuffers(indexBufferId); // pulled regions share the quad EBO
        }
        if (quadTextureId != 0) {
            GL11.glDeleteTextures(quadTextureId);
            quadTextureId = 0;
        }
        if (hizVisBufferId != 0) {
            GL15.glDeleteBuffers(hizVisBufferId);
            hizVisBufferId = 0;
            hizVisCapacity = 0;
        }
        hizHistoryVersion = -1;
        liveHandles.clear();
        liveCommands = 0;
        meshListVersion++;
        cancelPendingDecommit(0);
        cancelPendingDecommit(1);
        GpuMemoryTracker.getInstance()
            .untrack(GpuMemoryTracker.Category.CHUNK_MESH, trackedBytes);
        trackedBytes = 0;
    }
}
