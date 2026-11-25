package com.openmason.main.systems.animation;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.anim.AnimationAuthoring;
import com.openmason.engine.character.Retargeter;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.RigPoser;
import com.openmason.main.systems.character.CharacterDocument;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A clip from another characterized model (OpenMason #165, retarget preview): the source character (.omchar) and
 * its clip, posed by a read-only native authoring session for the side-by-side preview, and baked onto the open
 * animation's character through #160's {@link Retargeter} ({@link AnimationAuthoring#importRetargeted}: a layer,
 * one undo step). GL-free; main thread only.
 */
final class RetargetSource implements AutoCloseable {

    private final CharacterDocument character;
    private final AnimClipDocument clip;
    private final AnimationAuthoring player;
    private final Path clipFile;
    private final RigPoser poser = new RigPoser();
    private final JointPalette palette = new JointPalette();
    private float[] locals;

    private RetargetSource(CharacterDocument character, AnimClipDocument clip, AnimationAuthoring player, Path clipFile) {
        this.character = character;
        this.clip = clip;
        this.player = player;
        this.clipFile = clipFile;
    }

    static RetargetSource open(Path omchar, Path clipFile, Path projectRoot) throws IOException {
        CharacterDocument c = CharacterDocument.open(omchar, projectRoot);
        AnimClipDocument clip = null;
        try {
            clip = AnimClipDocument.read(Files.readAllBytes(clipFile));
            return new RetargetSource(c, clip, AnimationAuthoring.create(clip, c.rig(), c.definition()), clipFile);
        } catch (IOException | RuntimeException e) {
            if (clip != null) clip.close();
            c.close();
            throw e;
        }
    }

    CharacterDocument character() {
        return character;
    }

    Path clipFile() {
        return clipFile;
    }

    float duration() {
        return clip.header().duration();
    }

    /** The source pose at t (wrapped to the source clip's length). */
    JointPalette poseAt(float t) {
        float d = duration();
        float tt = d > 0 ? t % d : 0f;
        locals = player.pose(tt, AnimationAuthoring.STAGE_FINAL, locals);
        return poser.poseLocals(character.rig(), locals, new Matrix4f(), palette);
    }

    /** Bakes the source clip onto the target's character into authoring layer {@code layer}. */
    void bakeOnto(AnimationSession target, String layer) {
        var def = target.model().definition();
        try (Retargeter rt = Retargeter.create(character.rig(), character.definition(), target.model().rig(), def)) {
            target.authoring().importRetargeted(clip, rt, target.fps(), layer);
        }
    }

    @Override
    public void close() {
        player.close();
        clip.close();
        character.close();
    }
}
