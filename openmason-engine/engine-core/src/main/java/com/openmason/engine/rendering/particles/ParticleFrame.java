package com.openmason.engine.rendering.particles;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.function.Consumer;

/**
 * Per-frame camera and look inputs for building and drawing particles. Mutable and meant to be reused: the
 * host keeps one instance and rewrites it every frame, so nothing is allocated per frame.
 *
 * <p>Matrices and the camera position are in the host's <em>render space</em> (world translated by
 * {@code -origin}, see {@code RenderOrigin}); {@link #originX}/{@link #originY}/{@link #originZ} is that
 * origin in world coordinates. A host that renders in plain world space leaves the origin at zero.
 */
public final class ParticleFrame {

    public final Matrix4f view = new Matrix4f();
    /** Perspective projection (soft particles linearize depth with its m22/m32). */
    public final Matrix4f projection = new Matrix4f();
    /** Camera position in render space. */
    public final Vector3f cameraPosition = new Vector3f();
    public double originX, originY, originZ;

    /** Brightness applied to non-emissive particles by the default lighting (night, caves). */
    public float ambient = 1f;
    /** Exponential fog density per block (0 = no fog). */
    public float fogDensity;
    public final Vector3f fogColor = new Vector3f();
    /** Particles farther than this from the camera are culled (blocks). */
    public float maxDistance = Float.POSITIVE_INFINITY;

    /**
     * The sprite textures for {@link ParticleEffect.Sprite#TEXTURE} effects (null = none: textured particles draw
     * the missing-texture checker). Setting it is all a host does: the renderer binds the array and the batch
     * builder resolves the system's texture names to its layers.
     */
    public ParticleTextureArray sprites;

    /**
     * Scene depth for soft particles: a depth texture the size of the viewport holding the opaque scene, which
     * must <b>not</b> be attached to the framebuffer the particles draw into (copy or blit it first — sampling
     * an attached depth buffer is a feedback loop). 0 = soft particles off (effects keep hard edges).
     */
    public int depthTexture;

    /**
     * Optional split plane {@code a·x + b·y + c·z + d = 0} (render space), e.g. a water surface: particles on
     * the far side from the camera go to {@link ParticleRenderer#drawBehind()}, the rest to
     * {@link ParticleRenderer#drawFront()}, so a host can draw water between them.
     */
    public boolean split;
    public float splitA, splitB, splitC, splitD;

    /** Sets host uniforms (a {@link ParticleShaderExtension}'s) after the shader binds, every draw. */
    public Consumer<ShaderProgram> uniforms;

    /** Sets view, projection and camera position in one call. */
    public ParticleFrame camera(Matrix4fc view, Matrix4fc projection, float camX, float camY, float camZ) {
        this.view.set(view);
        this.projection.set(projection);
        this.cameraPosition.set(camX, camY, camZ);
        return this;
    }

    public ParticleFrame origin(double x, double y, double z) {
        originX = x;
        originY = y;
        originZ = z;
        return this;
    }

    public ParticleFrame sprites(ParticleTextureArray array) {
        sprites = array;
        return this;
    }

    /** Splits the draw at the horizontal plane {@code y = height} (render space) — a water surface. */
    public ParticleFrame splitAtHeight(float height) {
        return splitPlane(0f, 1f, 0f, -height);
    }

    public ParticleFrame splitPlane(float a, float b, float c, float d) {
        split = true;
        splitA = a;
        splitB = b;
        splitC = c;
        splitD = d;
        return this;
    }

    public ParticleFrame noSplit() {
        split = false;
        return this;
    }
}
