package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.Skin;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.CubeGeometry;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.skeleton.RigWiring;
import com.openmason.main.systems.skeleton.SkinStore;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OMO 1.8 rig round trip through the tool's own writer/reader: a part parented to a rotated bone
 * with a nonzero endpoint restores exactly (local vertices + transforms), the skeleton comes back,
 * and skin weights land on the same part-local vertices — and the engine reader agrees.
 */
@Tag("regression")
class OmoRigRoundTripTest {

    @TempDir
    Path dir;

    private static final float[] HAND_LOCAL = {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0.5f};
    private static final int[] HAND_INDICES = {0, 1, 2, 1, 3, 2};
    private static final float[] BODY_LOCAL = {-1, -1, 0, 1, -1, 0, 0, 1, 0};

    private final AtomicReference<PartMeshRebuilder.RebuildResult> last = new AtomicReference<>();

    @BeforeEach
    void nativeRig() {
        assumeTrue(CendaRig.isAvailable(), () -> "native rig unavailable: " + CendaRig.unavailableReason());
    }

    private record Session(ModelPartManager pm, BoneStore bones, SkinStore skins, String body, String hand) {}

    private Session build() {
        ModelPartManager pm = new ModelPartManager();
        pm.setMeshConsumer(last::set);
        BoneStore bones = new BoneStore();
        SkinStore skins = new SkinStore();
        RigWiring.connect(pm, bones, skins);

        ModelPartDescriptor body = pm.addPartFromGeometry("body",
                PartMeshRebuilder.PartGeometry.of(BODY_LOCAL.clone(), null, new int[]{0, 1, 2}, new int[]{0}),
                new Vector3f());
        pm.setPartTransform(body.id(), new PartTransform(new Vector3f(0, 0.5f, 0), new Vector3f(0.25f, 1, 0),
                new Vector3f(0, 30, 0), new Vector3f(1, 1, 1)));
        ModelPartDescriptor hand = pm.addPartFromGeometry("hand",
                PartMeshRebuilder.PartGeometry.of(HAND_LOCAL.clone(), null, HAND_INDICES.clone(), new int[]{0, 0}),
                new Vector3f(0.5f, 0, 0));
        pm.setPartTransform(hand.id(), new PartTransform(new Vector3f(0.5f, 0, 0), new Vector3f(0.1f, -0.2f, 0.3f),
                new Vector3f(15, -20, 5), new Vector3f(1.5f, 1, 0.5f)));

        // shoulder hangs under the body PART; forearm under shoulder; the hand hangs under forearm.
        bones.setBones(List.of(
                new OMOFormat.BoneEntry("shoulder", "Shoulder", body.id(),
                        0.2f, 0.4f, 0, 0, 0.1f, 0, 10, 0, -35, 0, 1.25f, 0.25f),
                new OMOFormat.BoneEntry("forearm", "Forearm", "shoulder",
                        0, 0, 0, 0.05f, 0, 0, 0, 40, 60, 0.3f, 0.9f, 0)));
        pm.setPartParent(hand.id(), "forearm");

        Skin skin = skins.ensureSkin(hand.id(), HAND_LOCAL, null);
        skin.set(1, new int[]{skins.jointIndex("forearm")}, new float[]{1f});
        skin.set(3, new int[]{skins.jointIndex("forearm"), skins.jointIndex("shoulder")}, new float[]{0.75f, 0.25f});
        return new Session(pm, bones, skins, body.id(), hand.id());
    }

    private Path save(Session s) {
        PartMeshRebuilder.RebuildResult r = last.get();
        OMOFormat.MeshData mesh = new OMOFormat.MeshData(r.combinedVertices(), r.combinedTexCoords(),
                r.combinedIndices(), r.triangleToFaceId(), "FLAT");
        List<OMOFormat.PartEntry> entries = OmoExportAssembler.extractPartEntries(s.pm(), mesh, true);
        OMOSerializer serializer = new OMOSerializer();
        serializer.setPartEntries(entries);
        serializer.setBoneEntries(s.bones().getBones());
        serializer.setSkinData(OmoSkinExport.toSavedLayout(s.skins(), s.pm(), s.bones(), entries));
        try {
            Path texture = dir.resolve("t.omt");
            Files.write(texture, new byte[]{1, 2, 3, 4});
            Path out = dir.resolve("rig.omo");
            org.junit.jupiter.api.Assertions.assertTrue(serializer.save(
                    new BlockModel("Rig", new CubeGeometry(), texture), out.toString(), mesh));
            return out;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void partUnderARotatedBoneRestoresExactly() throws Exception {
        Session s = build();
        Path file = save(s);

        OMODeserializer reader = new OMODeserializer();
        assertNotNull(reader.load(file.toString()));
        ModelPartManager loaded = new ModelPartManager();
        OmoPartRestorer.restore(loaded, reader.getLastLoadedMeshData(), reader.getLastLoadedPartEntries(),
                reader.getLastLoadedBoneEntries());

        assertEquals(s.bones().getBones(), reader.getLastLoadedBoneEntries());
        for (String id : List.of(s.body(), s.hand())) {
            ModelPartDescriptor before = s.pm().getPartById(id).orElseThrow();
            ModelPartDescriptor after = loaded.getPartById(id).orElseThrow();
            assertEquals(before.transform(), after.transform(), "transform of " + before.name());
            assertEquals(before.parentId(), after.parentId());
            assertArrayEquals(s.pm().getPartGeometry(id).vertices(), loaded.getPartGeometry(id).vertices(), 2e-5f,
                    "local vertices of " + before.name());
        }
    }

    @Test
    void skinWeightsComeBackOnTheSamePartLocalVertices() throws Exception {
        Session s = build();
        Path file = save(s);

        OMODeserializer reader = new OMODeserializer();
        assertNotNull(reader.load(file.toString()));
        OMOFormat.SkinData data = reader.getLastLoadedSkinData();
        assertNotNull(data);
        ModelPartManager loaded = new ModelPartManager();
        OmoPartRestorer.restore(loaded, reader.getLastLoadedMeshData(), reader.getLastLoadedPartEntries(),
                reader.getLastLoadedBoneEntries());
        SkinStore skins = new SkinStore();
        skins.load(data, id -> loaded.getPartGeometry(id) != null ? loaded.getPartGeometry(id).vertexCount() : 0);

        Skin skin = skins.skinOf(s.hand());
        assertNotNull(skin);
        assertEquals(2, skin.weightedCount());
        Skin.Influence v1 = skin.get(1);
        assertEquals("forearm", skins.jointIds().get(v1.joints()[0]));
        assertEquals(1f, v1.weights()[0], 1e-6f);
        Skin.Influence v3 = skin.get(3);
        assertEquals(2, v3.joints().length);
        assertEquals("forearm", skins.jointIds().get(v3.joints()[0]));
        assertEquals(0.75f, v3.weights()[0], 1e-6f);
        assertEquals("shoulder", skins.jointIds().get(v3.joints()[1]));
        assertNull(skins.skinOf(s.body()), "the body carries no weights");

        // The runtime reader sees the same skin + skeleton.
        try (InputStream in = Files.newInputStream(file)) {
            OMOReader.ReadResult rr = new OMOReader().read(in);
            assertEquals(data, rr.skin());
            assertEquals(2, rr.bones().size());
            assertEquals("1.8", rr.document().version());
        }
    }

    @Test
    void savedLayoutFollowsTheWrittenVertexOrder() {
        Session s = build();
        // Pretend the written mesh lists the hand's vertices in reverse (as a derived render mesh
        // may): written vertex k of the hand stands for soup vertex (handStart + 3 - k).
        int handStart = s.pm().getPartById(s.hand()).orElseThrow().meshRange().vertexStart();
        s.pm().setCombinedVertexResolver(i -> i >= handStart && i < handStart + 4
                ? new int[]{handStart + (3 - (i - handStart))} : new int[]{i});
        OMOFormat.PartEntry handEntry = new OMOFormat.PartEntry(s.hand(), "hand", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1,
                handStart, 4, 0, 6, 0, 2, true, false);
        OMOFormat.SkinData data = OmoSkinExport.toSavedLayout(s.skins(), s.pm(), s.bones(), List.of(handEntry));
        assertNotNull(data);
        List<Integer> locals = data.parts().getFirst().vertices().stream().map(OMOFormat.SkinVertex::localVertex).toList();
        assertEquals(List.of(0, 2), locals, "soup vertices 3 and 1 are written at 0 and 2");
        assertEquals(2, data.parts().getFirst().vertices().get(0).joints().length);
    }
}
