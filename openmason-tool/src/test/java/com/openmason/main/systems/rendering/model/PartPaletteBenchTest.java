package com.openmason.main.systems.rendering.model;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.Skin;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.skeleton.RigWiring;
import com.openmason.main.systems.skeleton.SkinStore;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #175 F2 numbers on a ~54k-triangle model (6 parts of 67 x 67 quads, one skinned): a part drag update,
 * a 6-part group move and a bone pose, old path (re-bake per change) vs the palette path. Headless (CPU side of a
 * frame; the GL upload of the palette is (1 + parts) x 64 bytes). Opt in with {@code -Dopenmason.bench.palette=true}.
 */
class PartPaletteBenchTest {

    private static final int GRID = 67;
    private static final int PARTS = 6;

    private static PartMeshRebuilder.PartGeometry grid(float ox) {
        int n = GRID + 1;
        float[] v = new float[n * n * 3];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int i = (z * n + x) * 3;
                v[i] = ox + x * 0.05f;
                v[i + 1] = (float) Math.sin(x * 0.2) * 0.1f;
                v[i + 2] = z * 0.05f;
            }
        }
        int[] idx = new int[GRID * GRID * 6];
        int[] faces = new int[GRID * GRID * 2];
        int k = 0, t = 0;
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int a = z * n + x, b = a + 1, c = a + n + 1, d = a + n;
                int[] q = {a, b, c, a, c, d};
                System.arraycopy(q, 0, idx, k, 6);
                k += 6;
                faces[t++] = z * GRID + x;
                faces[t++] = z * GRID + x;
            }
        }
        return PartMeshRebuilder.PartGeometry.of(v, new float[n * n * 2], idx, faces);
    }

    private static PartTransform moved(PartTransform t, int i) {
        return new PartTransform(t.origin(), new Vector3f(i * 0.01f, 0, 0), new Vector3f(0, i * 0.5f, 0),
                new Vector3f(1, 1, 1));
    }

    private static double ms(long nanos, int n) {
        return nanos / 1e6 / n;
    }

    @Test
    void bench() {
        assumeTrue(Boolean.getBoolean("openmason.bench.palette"), "opt in with -Dopenmason.bench.palette=true");
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        EditableModel model = new EditableModel();
        ModelPartManager pm = model.getPartManager();
        List<String> ids = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int p = 0; p < PARTS; p++) ids.add(pm.addPartFromGeometry("p" + p, grid(p * 4f), new Vector3f(p * 4f, 0, 0)).id());
        System.out.printf("[palette-bench] build %d tris: %.1f ms%n", model.getTriangleCount(), ms(System.nanoTime() - t0, 1));
        Matrix4f base = new Matrix4f();
        model.computePalette(base);

        // 1. part drag update
        String part = ids.get(2);
        PartTransform start = pm.getPartById(part).orElseThrow().transform();
        for (int i = 0; i < 2; i++) pm.setPartTransform(part, moved(start, i + 1)); // warm
        int oldN = 5;
        long t = System.nanoTime();
        for (int i = 0; i < oldN; i++) {
            pm.setPartTransform(part, moved(start, 10 + i));
        }
        double oldDrag = ms(System.nanoTime() - t, oldN);
        pm.beginTransformInteraction();
        long rebuilds = pm.getRebuildCount();
        int newN = 500;
        for (int i = 0; i < 50; i++) { pm.setPartTransform(part, moved(start, 100 + i)); model.computePalette(base); }
        t = System.nanoTime();
        for (int i = 0; i < newN; i++) {
            pm.setPartTransform(part, moved(start, 200 + i));
            model.computePalette(base);
        }
        double newDrag = ms(System.nanoTime() - t, newN);
        assertEquals(rebuilds, pm.getRebuildCount());
        t = System.nanoTime();
        pm.endTransformInteraction();
        double release = ms(System.nanoTime() - t, 1);
        t = System.nanoTime();
        model.computePalette(base);
        double rebind = ms(System.nanoTime() - t, 1);
        System.out.printf("[palette-bench] part drag update: old %.2f ms, palette %.4f ms; release re-bake %.1f ms"
                + " + corner re-bind %.2f ms%n", oldDrag, newDrag, release, rebind);

        // 2. group move of all parts
        t = System.nanoTime();
        for (int i = 0; i < 2; i++) {
            for (String id : ids) pm.setPartTransform(id, moved(pm.getPartById(id).orElseThrow().transform(), 300 + i));
        }
        double oldGroup = ms(System.nanoTime() - t, 2);
        t = System.nanoTime();
        for (int i = 0; i < 2; i++) {
            Map<String, PartTransform> u = new LinkedHashMap<>();
            for (String id : ids) u.put(id, moved(pm.getPartById(id).orElseThrow().transform(), 400 + i));
            pm.setPartTransformsBatch(u);
        }
        double batchGroup = ms(System.nanoTime() - t, 2);
        pm.beginTransformInteraction();
        t = System.nanoTime();
        for (int i = 0; i < newN; i++) {
            Map<String, PartTransform> u = new LinkedHashMap<>();
            for (String id : ids) u.put(id, moved(pm.getPartById(id).orElseThrow().transform(), 500 + i));
            pm.setPartTransformsBatch(u);
            model.computePalette(base);
        }
        double paletteGroup = ms(System.nanoTime() - t, newN);
        pm.endTransformInteraction();
        System.out.printf("[palette-bench] %d-part group move update: old (per part) %.1f ms, batch re-bake %.1f ms,"
                + " palette %.4f ms%n", PARTS, oldGroup, batchGroup, paletteGroup);

        // 3. bone pose (part 0 fully skinned to a bone)
        BoneStore bones = new BoneStore();
        SkinStore skins = new SkinStore();
        RigWiring.connect(pm, bones, skins);
        bones.setBones(List.of(new OMOFormat.BoneEntry("b", "b", null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)));
        float[] local = pm.getPartGeometry(ids.get(0)).vertices();
        Skin skin = skins.ensureSkin(ids.get(0), local, null);
        int b = skins.jointIndex("b");
        int pj = skins.jointIndex(ids.get(0));
        for (int v = 0; v < skin.vertexCount(); v++) {
            float w = (v % (GRID + 1)) / (float) GRID;
            skin.set(v, new int[]{b, pj}, new float[]{Math.max(0.01f, w), Math.max(0.01f, 1 - w)});
        }
        skins.fireChanged();
        t = System.nanoTime();
        for (int i = 0; i < 3; i++) pm.notifyExternalHierarchyChanged(); // the old pose cost (without its CPU deform)
        double oldPose = ms(System.nanoTime() - t, 3);
        rebuilds = pm.getRebuildCount();
        t = System.nanoTime();
        bones.setPose(Map.of("b", new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, 5))), id -> null, true);
        model.computePalette(base);
        double firstPose = ms(System.nanoTime() - t, 1);
        t = System.nanoTime();
        int poseN = 200;
        for (int i = 0; i < poseN; i++) {
            bones.setPose(Map.of("b", new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, i * 0.3f))),
                    id -> null, true);
            model.computePalette(base);
        }
        double pose = ms(System.nanoTime() - t, poseN);
        assertEquals(rebuilds, pm.getRebuildCount());
        System.out.printf("[palette-bench] bone pose update: old re-bake %.1f ms (+ CPU deform), palette %.3f ms"
                + " (first pose incl. skin re-pack %.1f ms)%n", oldPose, pose, firstPose);
    }
}
