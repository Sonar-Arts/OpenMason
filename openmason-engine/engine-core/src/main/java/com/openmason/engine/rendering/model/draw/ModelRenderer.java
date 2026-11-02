package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.rendering.model.IndexedDrawBatch;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shadow.ShadowUniforms;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * The engine model renderer: one draw path for every model mesh — animated
 * entities in a game and editable models in the Open Mason viewport — so a model
 * looks the same in the editor as in game.
 *
 * <p>Inputs are neutral: a {@link ModelGpuMesh}, its {@link ModelDrawPlan}, a
 * {@link MaterialTextures} resolver, the per-part matrices ({@link PartMatrices})
 * and a {@link ModelLighting}. Passes:
 * <ul>
 *   <li>{@link #drawLit} — textured, flat-normal lighting with CSM shadows, fog,
 *       self-glow and the host's {@link ModelShaderExtension} extra light.</li>
 *   <li>{@link #drawFlat} — one solid colour, depth-tested (untextured models,
 *       shadow casters into a bound depth target).</li>
 *   <li>{@link #drawWireframe} — see-through line overlay.</li>
 * </ul>
 * Each pass saves and restores the GL state it touches. Needs a GL context.
 */
public final class ModelRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ModelRenderer.class);

    private final ModelShaderExtension extension;

    private ShaderProgram litShader;
    private ShaderProgram flatShader;
    private boolean initialized;

    private boolean currentUseTexture;
    private final Vector3f scratch = new Vector3f();

    public ModelRenderer() {
        this(ModelShaderExtension.NONE);
    }

    public ModelRenderer(ModelShaderExtension extension) {
        this.extension = java.util.Objects.requireNonNull(extension, "extension");
    }

    /** Compiles the shaders. Must run on the GL thread; safe to call twice. */
    public void initialize() {
        if (initialized) return;
        litShader = build(ModelShaderSource.LIT_VERTEX, ModelShaderSource.litFragment(extension), "lit");
        flatShader = build(ModelShaderSource.FLAT_VERTEX, ModelShaderSource.FLAT_FRAGMENT, "flat");
        if (litShader != null) {
            for (String u : new String[]{"model", "view", "projection", "textureSampler", "u_cameraPos",
                    "u_fogDensity", "u_fogColor"}) {
                litShader.createUniform(u);
            }
            // Fully lit until the first draw sets the real environment.
            litShader.bind();
            litShader.setFloat("u_ambientLight", ModelLighting.PREVIEW_AMBIENT);
            litShader.setVec3("u_keyDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
            litShader.setFloat("u_skyLight", 1.0f);
            litShader.setBool("u_useTexture", true);
            currentUseTexture = true;
            ShadowUniforms.applyDisabled(litShader, extension.shadowTextureUnit());
            litShader.unbind();
        }
        if (flatShader != null) {
            for (String u : new String[]{"model", "view", "projection", "color"}) {
                flatShader.createUniform(u);
            }
        }
        initialized = true;
    }

    private static ShaderProgram build(String vertex, String fragment, String name) {
        try {
            ShaderProgram program = new ShaderProgram();
            program.createVertexShader(vertex);
            program.createFragmentShader(fragment);
            program.link();
            return program;
        } catch (Exception e) {
            logger.error("Failed to create model {} shader: {}", name, e.getMessage());
            return null;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** The lit-pass program, for hosts that apply their own uniforms (bound inside {@link #drawLit}'s hook). */
    public ShaderProgram litShader() {
        return litShader;
    }

    /** The shader extension this renderer was compiled with. */
    public ModelShaderExtension extension() {
        return extension;
    }

    /**
     * Lit pass. For every part, binds each textured batch's texture (see
     * {@link MaterialTextures} for 0 / negative results) and draws it with the
     * part's matrix.
     *
     * @param stateHook applies host uniforms to the bound lit shader after the
     *                  engine's (point lights, the frame's shadow cascades); null
     *                  disables shadows
     */
    public void drawLit(ModelGpuMesh mesh, ModelDrawPlan plan, MaterialTextures textures,
                        PartMatrices parts, Matrix4f view, Matrix4f projection,
                        ModelLighting lighting, Consumer<ShaderProgram> stateHook) {
        if (!initialized || litShader == null || !drawable(mesh, plan)) return;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean wasCullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDisable(GL11.GL_CULL_FACE);

        ShaderProgram shader = litShader;
        shader.bind();
        shader.setUniform("view", view);
        shader.setUniform("projection", projection);
        shader.setUniform("textureSampler", 0);
        shader.setUniform("u_cameraPos", scratch.set(lighting.cameraPos()));
        shader.setUniform("u_fogDensity", lighting.fogDensity());
        shader.setUniform("u_fogColor", scratch.set(lighting.fogColor()));
        shader.setFloat("u_ambientLight", lighting.ambient());
        shader.setVec3("u_keyDirection", scratch.set(lighting.keyDirection()));
        shader.setFloat("u_skyLight", lighting.skyLight());
        shader.setFloat("u_selfGlow", lighting.selfGlow());
        shader.setVec3("u_baseColor", scratch.set(lighting.baseColor()));
        if (stateHook != null) {
            stateHook.accept(shader);
        } else {
            ShadowUniforms.applyDisabled(shader, extension.shadowTextureUnit());
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(mesh.vao());

        parts.forEachPart((partIndex, partMatrix) -> {
            shader.setUniform("model", partMatrix);
            for (IndexedDrawBatch batch : plan.forPart(partIndex).textured()) {
                int texture = textures.textureFor(batch.materialId());
                if (texture < 0) continue;
                setUseTexture(shader, texture > 0);
                if (texture > 0) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                }
                GL11.glDrawElements(GL11.GL_TRIANGLES, batch.indexCount(),
                        GL11.GL_UNSIGNED_INT, batch.byteOffset());
            }
        });
        setUseTexture(shader, true);

        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.unbind();

        if (wasCullFaceEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        GL20.glUseProgram(previousProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GL30.glBindVertexArray(previousVertexArray);
    }

    private void setUseTexture(ShaderProgram shader, boolean use) {
        if (use != currentUseTexture) {
            shader.setBool("u_useTexture", use);
            currentUseTexture = use;
        }
    }

    /**
     * Flat-colour pass: every face in {@code color}, no texture, depth-tested.
     * Geometry and part matrices are identical to {@link #drawLit}.
     */
    public void drawFlat(ModelGpuMesh mesh, ModelDrawPlan plan, PartMatrices parts,
                         Matrix4f view, Matrix4f projection, Vector4f color) {
        if (!initialized || flatShader == null || !drawable(mesh, plan)) return;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean wasCullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDisable(GL11.GL_CULL_FACE);

        drawUntextured(mesh, plan, parts, view, projection, color);

        if (wasCullFaceEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        GL20.glUseProgram(previousProgram);
        GL30.glBindVertexArray(previousVertexArray);
    }

    /**
     * See-through wireframe overlay of the model's own triangles in {@code color}:
     * depth test off, so it doubles as a locator through geometry.
     */
    public void drawWireframe(ModelGpuMesh mesh, ModelDrawPlan plan, PartMatrices parts,
                              Matrix4f view, Matrix4f projection, Vector4f color) {
        if (!initialized || flatShader == null || !drawable(mesh, plan)) return;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean wasCullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasDepthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int[] previousPolygonMode = new int[2];
        GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, previousPolygonMode);
        float previousLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        GL11.glLineWidth(1.5f);

        drawUntextured(mesh, plan, parts, view, projection, color);

        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, previousPolygonMode[0]);
        GL11.glLineWidth(previousLineWidth);
        if (wasCullFaceEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        if (wasDepthTestEnabled) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
        GL20.glUseProgram(previousProgram);
        GL30.glBindVertexArray(previousVertexArray);
    }

    private void drawUntextured(ModelGpuMesh mesh, ModelDrawPlan plan, PartMatrices parts,
                                Matrix4f view, Matrix4f projection, Vector4f color) {
        ShaderProgram shader = flatShader;
        shader.bind();
        shader.setUniform("view", view);
        shader.setUniform("projection", projection);
        shader.setUniform("color", color);

        GL30.glBindVertexArray(mesh.vao());
        parts.forEachPart((partIndex, partMatrix) -> {
            shader.setUniform("model", partMatrix);
            for (IndexedDrawBatch batch : plan.forPart(partIndex).untextured()) {
                GL11.glDrawElements(GL11.GL_TRIANGLES, batch.indexCount(),
                        GL11.GL_UNSIGNED_INT, batch.byteOffset());
            }
        });
        GL30.glBindVertexArray(0);
        shader.unbind();
    }

    private static boolean drawable(ModelGpuMesh mesh, ModelDrawPlan plan) {
        return mesh != null && plan != null && mesh.isCreated() && mesh.indexCount() > 0;
    }

    /** Release the shaders (meshes are owned by their callers). */
    public void cleanup() {
        if (!initialized) return;
        if (litShader != null) litShader.cleanup();
        if (flatShader != null) flatShader.cleanup();
        litShader = null;
        flatShader = null;
        initialized = false;
    }
}
