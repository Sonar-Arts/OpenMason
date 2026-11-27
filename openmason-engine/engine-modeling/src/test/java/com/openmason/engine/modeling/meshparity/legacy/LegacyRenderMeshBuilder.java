package com.openmason.engine.modeling.meshparity.legacy;

/* FROZEN COPY (test scope) of the pre-#175-F3 Java class of the same name minus "Legacy" in
 * com.openmason.engine.modeling.editable, kept verbatim as the parity reference for the native mesh pipeline
 * (MeshPipelineParityTest). Do not "fix" it: a difference to the native port must show up as a parity break. */

import com.openmason.engine.modeling.editable.EditableFace;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.editable.RenderMesh;

import com.openmason.engine.modeling.ModelingConstants;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import com.openmason.engine.modeling.uv.IFaceTextureManager;
import com.openmason.engine.modeling.uv.MaterialDefinition;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the render representation from an {@link EditableMesh}: emits one
 * corner per face-loop vertex (per-face duplication — a cube stays 24 render
 * vertices), projects corner UVs into each face's
 * {@link FaceTextureMapping.UVRegion}, and triangulates loops via
 * {@link LegacyPolygonTriangulator}.
 *
 * <p>Because corners are never shared across faces, material seams need no
 * special handling — the legacy seam-duplication/strip machinery is
 * unnecessary by construction.
 *
 * <p>The UV projection is the same tangent-frame region mapping the legacy
 * {@code PerFaceUVCoordinateGenerator} applied on every rebuild. A face's
 * AUTHORED corner UVs win over the projection when the face has no explicit
 * mapping, or when its mapping says they are authored
 * ({@link FaceTextureMapping#authoredUVs()}: an imported / loaded UV layout
 * that is not the region projection — before #169 those were thrown away and
 * every mapped face was re-projected).
 */
public final class LegacyRenderMeshBuilder {

    private LegacyRenderMeshBuilder() {
        // Static utility — no instantiation
    }

    /**
     * Build the render mesh for all live faces, in face insertion order.
     *
     * @param mesh           Source mesh
     * @param textureManager Per-face UV region/rotation source; faces without a
     *                       mapping get the full region (legacy behavior).
     *                       May be {@code null} (all faces full-region).
     */
    public static RenderMesh build(EditableMesh mesh, IFaceTextureManager textureManager) {
        int cornerTotal = 0;
        int triangleTotal = 0;
        for (EditableFace face : mesh.faces()) {
            cornerTotal += face.loopLength();
            triangleTotal += face.loopLength() - 2;
        }

        float[] vertices = new float[cornerTotal * 3];
        float[] texCoords = new float[cornerTotal * 2];
        int[] indices = new int[triangleTotal * 3];
        int[] triangleToFaceId = new int[triangleTotal];
        int[] cornerToVertexId = new int[cornerTotal];

        List<List<Integer>> cornersPerVertex = new ArrayList<>(mesh.vertexCount());
        for (int v = 0; v < mesh.vertexCount(); v++) {
            cornersPerVertex.add(new ArrayList<>(4));
        }

        int corner = 0;
        int triangle = 0;
        Vector3f pos = new Vector3f();

        for (EditableFace face : mesh.faces()) {
            int n = face.loopLength();
            int firstCorner = corner;
            Vector3f[] loopPositions = new Vector3f[n];

            for (int i = 0; i < n; i++) {
                int vertexId = face.vertexAt(i);
                mesh.position(vertexId, pos);
                loopPositions[i] = new Vector3f(pos);

                vertices[corner * 3]     = pos.x;
                vertices[corner * 3 + 1] = pos.y;
                vertices[corner * 3 + 2] = pos.z;
                cornerToVertexId[corner] = vertexId;
                cornersPerVertex.get(vertexId).add(corner);
                corner++;
            }

            emitFaceUVs(face, loopPositions, texCoords, firstCorner,
                mapping(textureManager, face.faceId()));

            int[] local = LegacyPolygonTriangulator.triangulate(loopPositions);
            for (int i = 0; i < local.length; i += 3) {
                indices[triangle * 3]     = firstCorner + local[i];
                indices[triangle * 3 + 1] = firstCorner + local[i + 1];
                indices[triangle * 3 + 2] = firstCorner + local[i + 2];
                triangleToFaceId[triangle] = face.faceId();
                triangle++;
            }
        }

        int[][] vertexIdToCorners = new int[mesh.vertexCount()][];
        for (int v = 0; v < mesh.vertexCount(); v++) {
            List<Integer> list = cornersPerVertex.get(v);
            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = list.get(i);
            }
            vertexIdToCorners[v] = arr;
        }

        return new RenderMesh(vertices, texCoords, indices,
            triangleToFaceId, cornerToVertexId, vertexIdToCorners);
    }

    private static FaceTextureMapping mapping(IFaceTextureManager textureManager, int faceId) {
        return textureManager != null ? textureManager.getFaceMapping(faceId) : null;
    }

    /**
     * Emit a face's corner UVs. Faces with an explicit non-default mapping are
     * projected into their region unless the mapping marks the face's UVs as
     * authored; faces without one keep their AUTHORED per-corner UVs when
     * present (sphere lat/long grids, hand-authored UVs).
     */
    private static void emitFaceUVs(EditableFace face, Vector3f[] loop, float[] texCoords,
                                    int firstCorner, FaceTextureMapping faceMapping) {
        boolean explicitMapping = isExplicit(faceMapping);

        float[] authored = face.cornerUVs();
        if (authored != null && (!explicitMapping || faceMapping.authoredUVs())) {
            for (int i = 0; i < loop.length; i++) {
                texCoords[(firstCorner + i) * 2]     = authored[i * 2];
                texCoords[(firstCorner + i) * 2 + 1] = authored[i * 2 + 1];
            }
            return;
        }

        projectFaceUVs(loop, texCoords, firstCorner, faceMapping);
    }

    /** True when a mapping is not the implicit default (another material, or a sub-region). */
    public static boolean isExplicit(FaceTextureMapping mapping) {
        return mapping != null
            && (mapping.materialId() != MaterialDefinition.DEFAULT.materialId()
                || !mapping.uvRegion().equals(FaceTextureMapping.FULL_REGION));
    }

    /**
     * True when the face's authored corner UVs equal (within {@code tolerance}
     * per coordinate) what the projection into {@code mapping}'s region gives —
     * UVs the editor itself projected and saved, as opposed to an authored UV
     * layout. False for a face without authored UVs.
     */
    public static boolean authoredUVsMatchProjection(EditableMesh mesh, EditableFace face,
                                                     FaceTextureMapping mapping, float tolerance) {
        float[] authored = face.cornerUVs();
        if (authored == null) {
            return false;
        }
        int n = face.loopLength();
        Vector3f[] loop = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            loop[i] = mesh.position(face.vertexAt(i));
        }
        float[] projected = new float[n * 2];
        projectFaceUVs(loop, projected, 0, mapping);
        for (int i = 0; i < projected.length; i++) {
            if (Math.abs(projected[i] - authored[i]) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /**
     * Project a face's loop positions into its UV region: Newell normal →
     * tangent frame → normalize projected bounds to 0..1 → V-flip → rotation →
     * region. Identical math to the legacy per-face generator, minus the
     * robust-normal brute force (the authoritative loop makes Newell exact).
     */
    private static void projectFaceUVs(Vector3f[] loop, float[] texCoords, int firstCorner,
                                       FaceTextureMapping faceMapping) {
        FaceTextureMapping.UVRegion uvRegion = (faceMapping != null)
            ? faceMapping.uvRegion()
            : FaceTextureMapping.FULL_REGION;
        FaceTextureMapping.UVRotation rotation = (faceMapping != null)
            ? faceMapping.uvRotation()
            : FaceTextureMapping.UVRotation.NONE;

        int n = loop.length;
        Vector3f normal = LegacyPolygonTriangulator.newellNormal(loop);
        // Tangent-frame validation expects a direction, not an area-scaled normal.
        if (normal.lengthSquared() > 0) normal.normalize();
        Vector3f[] frame = computeTangentFrame(normal);
        if (frame == null) {
            // Degenerate face — center of the region for every corner.
            for (int i = 0; i < n; i++) {
                texCoords[(firstCorner + i) * 2]     = uvRegion.u0() + 0.5f * uvRegion.width();
                texCoords[(firstCorner + i) * 2 + 1] = uvRegion.v0() + 0.5f * uvRegion.height();
            }
            return;
        }

        Vector3f tangent = frame[0];
        Vector3f bitangent = frame[1];
        Vector3f ref = loop[0];

        float[] projS = new float[n];
        float[] projT = new float[n];
        float minS = Float.MAX_VALUE, maxS = -Float.MAX_VALUE;
        float minT = Float.MAX_VALUE, maxT = -Float.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            float dx = loop[i].x - ref.x;
            float dy = loop[i].y - ref.y;
            float dz = loop[i].z - ref.z;
            projS[i] = dx * tangent.x + dy * tangent.y + dz * tangent.z;
            projT[i] = dx * bitangent.x + dy * bitangent.y + dz * bitangent.z;
            minS = Math.min(minS, projS[i]);
            maxS = Math.max(maxS, projS[i]);
            minT = Math.min(minT, projT[i]);
            maxT = Math.max(maxT, projT[i]);
        }

        float rangeS = maxS - minS;
        float rangeT = maxT - minT;

        for (int i = 0; i < n; i++) {
            float s = (rangeS > ModelingConstants.POSITION_EPSILON) ? (projS[i] - minS) / rangeS : 0.5f;
            float t = (rangeT > ModelingConstants.POSITION_EPSILON) ? (projT[i] - minT) / rangeT : 0.5f;

            // Flip V so texture top maps to face top (OpenGL UV origin is bottom-left).
            t = 1.0f - t;

            if (rotation != FaceTextureMapping.UVRotation.NONE) {
                float rs, rt;
                switch (rotation) {
                    case CW_90  -> { rs = t;        rt = 1.0f - s; }
                    case CW_180 -> { rs = 1.0f - s; rt = 1.0f - t; }
                    case CW_270 -> { rs = 1.0f - t; rt = s;        }
                    default     -> { rs = s;        rt = t;        }
                }
                s = rs;
                t = rt;
            }

            texCoords[(firstCorner + i) * 2]     = uvRegion.u0() + s * uvRegion.width();
            texCoords[(firstCorner + i) * 2 + 1] = uvRegion.v0() + t * uvRegion.height();
        }
    }

    // ── FaceProjectionUtil.computeTangentFrame (verbatim, frozen with this copy) ──

    private static final float TANGENT_EPSILON = ModelingConstants.POSITION_EPSILON;

    private static Vector3f[] computeTangentFrame(Vector3f normal) {
        if (normal.lengthSquared() < TANGENT_EPSILON * TANGENT_EPSILON) {
            return null;
        }
        Vector3f n = new Vector3f(normal).normalize();
        Vector3f reference = (Math.abs(n.y) > 0.9f)
            ? new Vector3f(0.0f, 0.0f, -1.0f)
            : new Vector3f(0.0f, 1.0f, 0.0f);
        Vector3f tangent = new Vector3f(reference).cross(n).normalize();
        Vector3f bitangent = new Vector3f(n).cross(tangent).normalize();
        return new Vector3f[]{tangent, bitangent};
    }
}
