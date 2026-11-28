package com.openmason.main.systems.animation;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.rig.BoneMask;
import com.openmason.engine.rig.FirstPersonProjection;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * The "Eyes" preview: a character seen by itself in first person (OpenMason #161 frame, Stonebreak #462 usage), for
 * the Animation and Animation Graph viewports. Not the Scene viewport's free-fly "First-Person" camera.
 *
 * <p>Per frame ({@link #update}): the camera sits on the character's eye point ({@code cc_char_eyes_point}: its
 * {@code eyes} socket, else the eye roles, else the head) of the BODY pose and looks along {@code yaw}/{@code pitch}
 * (degrees, from the character's facing -Z; + yaw = left, + pitch = up). The first-person pose is posed again under
 * {@code pull . attach . base}: {@code attach} turns it about the eye with the look when {@link #armsFollowLook}
 * (camera-attached arms, how a game anchors first-person clips that key the torso at rest), the engine's
 * {@link FirstPersonProjection} supplies the own-FOV lens, depth slab and pull, and everything but the arm branches is
 * collapsed ({@code cc_char_view_mask(FIRST_PERSON)}). Draw {@link #palette()} with {@link #view()} and
 * {@link #projection()}; world geometry (the ground) with {@link #worldProjection()}. GL-free.
 */
public final class EyesView implements AutoCloseable {

    /** Re-poses the shown pose under a base matrix (a clip's locals, a graph's first-person view). */
    @FunctionalInterface
    public interface Poser {
        JointPalette pose(Matrix4fc base, JointPalette out);
    }

    public static final float DEFAULT_FOV = 70f;

    private float yaw;
    private float pitch;
    private float fovDegrees = DEFAULT_FOV;
    private boolean armsFollowLook = true;

    private final FirstPersonProjection lens = new FirstPersonProjection().fovY(DEFAULT_FOV);
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f worldProjection = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f pull = new Matrix4f();
    private final Matrix4f look = new Matrix4f();
    private final Matrix4f base = new Matrix4f();
    private final Vector3f eyes = new Vector3f();
    private final Vector3f forward = new Vector3f();
    private final Vector3f up = new Vector3f();
    private final JointPalette palette = new JointPalette();
    private BoneMask mask;
    private Rig maskRig;
    private CharacterDefinition maskCharacter;
    private String problem;

    /**
     * Poses this frame's first-person view; false (see {@link #problem()}) when the model has no character, no eye
     * point or no arm branches.
     *
     * @param body      the shown pose (model space under {@code modelBase}), read before anything is hidden
     * @param modelBase the base the shown pose was posed with (root-motion in-place preview, else identity)
     */
    public boolean update(Rig rig, CharacterDefinition character, JointPalette body, Matrix4fc modelBase, Poser poser,
                          int width, int height) {
        problem = null;
        if (rig == null || character == null) {
            problem = "the model has no character (open an .omchar)";
            return false;
        }
        if (body == null || width < 8 || height < 8) {
            problem = "nothing posed";
            return false;
        }
        try {
            character.eyesPoint(rig, body, eyes);
            ensureMask(rig, character);
        } catch (RuntimeException e) {
            problem = "first person: " + e.getMessage();
            return false;
        }
        look.identity().rotateY((float) Math.toRadians(yaw)).rotateX((float) Math.toRadians(pitch));
        look.transformDirection(forward.set(0f, 0f, -1f));
        look.transformDirection(up.set(0f, 1f, 0f));
        view.setLookAt(eyes.x, eyes.y, eyes.z, eyes.x + forward.x, eyes.y + forward.y, eyes.z + forward.z,
                up.x, up.y, up.z);
        worldProjection.setPerspective((float) Math.toRadians(fovDegrees), width / (float) height, 0.05f, 400f);
        lens.fovY(fovDegrees).compute(view, worldProjection, projection, pull);
        base.set(pull);
        if (armsFollowLook) {
            base.translate(eyes).mul(look).translate(-eyes.x, -eyes.y, -eyes.z);
        }
        base.mul(modelBase);
        if (poser.pose(base, palette) == null) {
            problem = "nothing posed";
            return false;
        }
        palette.hide(rig, mask);
        return true;
    }

    private void ensureMask(Rig rig, CharacterDefinition character) {
        if (mask != null && maskRig == rig && maskCharacter == character) return;
        if (mask != null) mask.close();
        mask = BoneMask.create(rig, 0f);
        character.viewMask(rig, CharacterDefinition.VIEW_FIRST_PERSON, mask);
        maskRig = rig;
        maskCharacter = character;
    }

    /** Turns the look by a mouse drag (pixels). */
    public void drag(float dx, float dy) {
        setLook(yaw - dx * 0.25f, pitch - dy * 0.25f);
    }

    public void setLook(float yawDegrees, float pitchDegrees) {
        yaw = wrap(yawDegrees);
        pitch = Math.max(-89f, Math.min(89f, pitchDegrees));
    }

    public void setFov(float degrees) {
        fovDegrees = Math.max(20f, Math.min(120f, degrees));
    }

    public void setArmsFollowLook(boolean follow) {
        armsFollowLook = follow;
    }

    public void reset() {
        yaw = 0f;
        pitch = 0f;
        fovDegrees = DEFAULT_FOV;
        armsFollowLook = true;
    }

    private static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float fov() {
        return fovDegrees;
    }

    public boolean armsFollowLook() {
        return armsFollowLook;
    }

    /** The eye point of the last update (model space). */
    public Vector3f eyes() {
        return eyes;
    }

    public Matrix4f view() {
        return view;
    }

    /** The first-person lens (draw {@link #palette()} with it). */
    public Matrix4f projection() {
        return projection;
    }

    /** The plain perspective at the same FOV (world geometry: the ground). */
    public Matrix4f worldProjection() {
        return worldProjection;
    }

    /** The first-person pose: arms only, lens-pulled toward the eye. */
    public JointPalette palette() {
        return palette;
    }

    /** Why the last update failed (null after a success). */
    public String problem() {
        return problem;
    }

    @Override
    public void close() {
        if (mask != null) mask.close();
        mask = null;
    }
}
