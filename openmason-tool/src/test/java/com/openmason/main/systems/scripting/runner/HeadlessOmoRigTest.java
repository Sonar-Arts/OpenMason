package com.openmason.main.systems.scripting.runner;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.Skin;
import com.openmason.main.systems.rendering.model.io.omo.OMODeserializer;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The headless writer saves the document's skeleton and skin like the live save (OMO 1.8). */
class HeadlessOmoRigTest {

    @Test
    void writesBonesAndSkin(@TempDir Path tmp) {
        assumeTrue(CendaRig.isAvailable(), () -> "native rig unavailable: " + CendaRig.unavailableReason());
        HeadlessModelDocument doc = new HeadlessModelDocument();
        float[] local = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        ModelPartDescriptor part = doc.parts().addPartFromGeometry("blade",
                PartMeshRebuilder.PartGeometry.of(local, null, new int[]{0, 1, 2}, new int[]{0}), new Vector3f());
        OMOFormat.BoneEntry bone = new OMOFormat.BoneEntry("b", "Hilt", null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0);
        doc.bones().setBones(List.of(bone));
        Skin skin = doc.skins().ensureSkin(part.id(), local, null);
        skin.set(2, new int[]{doc.skins().jointIndex("b")}, new float[]{1f});

        Path out = tmp.resolve("blade.omo");
        HeadlessOmoWriter.write(doc, out.toString(), "Blade");

        OMODeserializer reader = new OMODeserializer();
        assertNotNull(reader.load(out.toString()));
        assertEquals(List.of(bone), reader.getLastLoadedBoneEntries());
        assertNotNull(reader.getLastLoadedPartEntries(), "a weighted lone part still writes its entry");
        OMOFormat.SkinData data = reader.getLastLoadedSkinData();
        assertNotNull(data);
        assertEquals(List.of("b"), data.jointIds());
        assertEquals(part.id(), data.parts().getFirst().partId());
        assertEquals(2, data.parts().getFirst().vertices().getFirst().localVertex());
    }
}
