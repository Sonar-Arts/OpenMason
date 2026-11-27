package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.rendering.model.IndexedDrawBatch;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shadow.ShadowUniforms;
import com.openmason.engine.rig.JointPalette;
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
 * {@link MaterialTextures} resolver, the pose — either per-part rigid matrices
 * ({@link PartMatrices}) or a native rig's {@link JointPalette} (GPU skinning through
 * {@link ModelSkinGlsl} when the mesh carries skin attributes) — and a
 * {@link ModelLighting}. Passes:
 * <ul>
 *   <li>{@link #drawLit} — textured (times the material's base colour), lit with the mesh's corner normals
 *       ({@link ModelNormalGlsl}: smooth where the model says so, the exact face normal elsewhere) with CSM
 *       shadows, fog, self-glow and the host's {@link ModelShaderExtension} extra light.</li>
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
    private final JointPaletteTexture paletteTexture = new JointPaletteTexture();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector4f WHITE = new Vector4f(1f);
    private final Vector4f materialColor = new Vector4f(1f);
    private float[] currentMaterialColor;

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
                    "u_fogDensity", "u_fogColor", "u_jointPalette", "u_skinned", "u_skinInfluences"}) {
                litShader.createUniform(u);
            }
            // Fully lit until the first draw sets the real environment.
            litShader.bind();
            litShader.setFloat("u_ambientLight", ModelLighting.PREVIEW_AMBIENT);
            litShader.setVec3("u_keyDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
            litShader.setFloat("u_skyLight", 1.0f);
            litShader.setBool("u_useTexture", true);
            litShader.setUniform("u_jointPalette", JointPaletteTexture.DEFAULT_UNIT);
            litShader.setUniform("u_skinned", false);
            litShader.setUniform("u_skinInfluences", ModelSkinGlsl.INFLUENCES_UNIT);
            litShader.setBool("u_hasNormals", false);
            litShader.setVec4("u_materialColor", WHITE);
            litShader.setInt("u_dyeMode", 0);
            litShader.setInt("u_dyeMask", ModelAppearance.MASK_UNIT);
            ModelMorphGlsl.bindSamplerUnits(litShader);
            currentUseTexture = true;
            ShadowUniforms.applyDisabled(litShader, extension.shadowTextureUnit());
            litShader.unbind();
        }
        if (flatShader != null) {
            for (String u : new String[]{"model", "view", "projection", "color", "u_jointPalette", "u_skinned",
                    "u_skinInfluences"}) {
                flatShader.createUniform(u);
            }
            flatShader.bind();
            flatShader.setUniform("u_jointPalette", JointPaletteTexture.DEFAULT_UNIT);
            flatShader.setUniform("u_skinned", false);
            flatShader.setUniform("u_skinInfluences", ModelSkinGlsl.INFLUENCES_UNIT);
            ModelMorphGlsl.bindSamplerUnits(flatShader);
            flatShader.unbind();
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
        drawLit(mesh, plan, textures, parts, null, view, projection, lighting, stateHook, null);
    }

    /**
     * Lit pass posed by a native rig's palette: GPU-skinned when the mesh carries skin attributes,
     * otherwise each part draws rigidly with its joint's skin matrix (part i = joint i).
     */
    public void drawLit(ModelGpuMesh mesh, ModelDrawPlan plan, MaterialTextures textures,
                        JointPalette palette, Matrix4f view, Matrix4f projection,
                        ModelLighting lighting, Consumer<ShaderProgram> stateHook) {
        drawLit(mesh, plan, textures, palette, view, projection, lighting, stateHook, null);
    }

    /**
     * Lit pass posed by a palette with an appearance (OpenMason #162): hidden parts issue no draw call, dyed
     * materials are tinted, hidden / kept vertex groups cut the batches down to their visible ranges (#172) and the
     * appearance's morph weights deform the mesh (#171; see {@link ModelAppearance}). {@code appearance} null = the
     * plain pass (a mesh with shape keys draws its default weights).
     */
    public void drawLit(ModelGpuMesh mesh, ModelDrawPlan plan, MaterialTextures textures,
                        JointPalette palette, Matrix4f view, Matrix4f projection,
                        ModelLighting lighting, Consumer<ShaderProgram> stateHook, ModelAppearance appearance) {
        if (mesh != null && mesh.hasSkin()) {
            drawLit(mesh, plan, textures, everyPart(plan), palette, view, projection, lighting, stateHook, appearance);
        } else {
            drawLit(mesh, plan, textures, rigidParts(plan, palette), null, view, projection, lighting, stateHook,
                appearance);
        }
    }

    private void drawLit(ModelGpuMesh mesh, ModelDrawPlan plan, MaterialTextures textures,
                         PartMatrices parts, JointPalette skinPalette, Matrix4f view, Matrix4f projection,
                         ModelLighting lighting, Consumer<ShaderProgram> stateHook, ModelAppearance appearance) {
        if (!initialized || litShader == null || !drawable(mesh, plan)) return;
        if (mesh == captureMesh) capture.set(mesh, plan, appearance, parts, textures, skinPalette);
        plan = plan.forAppearance(appearance);
        ModelDrawPlan drawPlan = plan;

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

        beginSkin(shader, skinPalette, mesh);
        boolean morphed = mesh.beginMorphs(shader, appearance != null ? appearance.morphWeights() : null);
        shader.setBool("u_hasNormals", mesh.hasNormals());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(mesh.vao());

        boolean dyed = appearance != null && appearance.hasDyes();
        currentDye = null;
        currentMaterialColor = null;
        shader.setVec4("u_materialColor", WHITE);
        parts.forEachPart((partIndex, partMatrix) -> {
            if (appearance != null && !appearance.partVisible(partIndex)) return;
            shader.setUniform("model", partMatrix);
            for (IndexedDrawBatch batch : drawPlan.forPart(partIndex).textured()) {
                int texture = textures.textureFor(batch.materialId());
                if (texture < 0) continue;
                setUseTexture(shader, texture > 0);
                if (dyed) applyDye(shader, appearance.dye(batch.materialId()));
                applyMaterialColor(shader, textures.baseColorFor(batch.materialId()));
                if (texture > 0) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                }
                GL11.glDrawElements(GL11.GL_TRIANGLES, batch.indexCount(),
                        GL11.GL_UNSIGNED_INT, batch.byteOffset());
            }
        });
        if (dyed) {
            applyDye(shader, null);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + ModelAppearance.MASK_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        setUseTexture(shader, true);
        applyMaterialColor(shader, null);
        if (morphed) ModelGpuMesh.endMorphs(shader);
        endSkin(shader, skinPalette);

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

    private ModelAppearance.Dye currentDye;
    private final Vector3f dyeScratch = new Vector3f();
    private static final String[] DYE_TINTS = {"u_dyeTints[0]", "u_dyeTints[1]", "u_dyeTints[2]", "u_dyeTints[3]"};

    /** Sets the dye uniforms for the next batch (null = undyed); skips a repeat of the current dye. */
    private void applyDye(ShaderProgram shader, ModelAppearance.Dye dye) {
        if (dye == currentDye) return;
        currentDye = dye;
        if (dye == null) {
            shader.setInt("u_dyeMode", 0);
            return;
        }
        float[] t = dye.tints();
        for (int k = 0; k < 4; k++) shader.setVec3(DYE_TINTS[k], dyeScratch.set(t[k * 3], t[k * 3 + 1], t[k * 3 + 2]));
        if (dye.maskTexture() > 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + ModelAppearance.MASK_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, dye.maskTexture());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            shader.setInt("u_dyeMode", 2);
        } else {
            shader.setInt("u_dyeMode", 1);
        }
    }

    /** Sets the material base colour for the next batch (null = white); skips a repeat. */
    private void applyMaterialColor(ShaderProgram shader, float[] rgba) {
        if (rgba == currentMaterialColor) return;
        currentMaterialColor = rgba;
        if (rgba == null || rgba.length < 4) {
            shader.setVec4("u_materialColor", WHITE);
        } else {
            shader.setVec4("u_materialColor", materialColor.set(rgba[0], rgba[1], rgba[2], rgba[3]));
        }
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
        drawFlat(mesh, plan, parts, null, view, projection, color, null);
    }

    /** Flat-colour pass posed by a native rig's palette (see the lit overload). */
    public void drawFlat(ModelGpuMesh mesh, ModelDrawPlan plan, JointPalette palette,
                         Matrix4f view, Matrix4f projection, Vector4f color) {
        drawFlat(mesh, plan, palette, view, projection, color, null);
    }

    /** Flat-colour pass posed by a palette; parts {@code appearance} hides issue no draw call (null = all draw). */
    public void drawFlat(ModelGpuMesh mesh, ModelDrawPlan plan, JointPalette palette,
                         Matrix4f view, Matrix4f projection, Vector4f color, ModelAppearance appearance) {
        PartMatrices parts = mesh != null && mesh.hasSkin() ? everyPart(plan) : rigidParts(plan, palette);
        if (appearance != null) parts = visibleOnly(parts, appearance);
        drawFlat(mesh, plan, parts, mesh != null && mesh.hasSkin() ? palette : null, view, projection, color,
                appearance);
    }

    private static PartMatrices visibleOnly(PartMatrices parts, ModelAppearance appearance) {
        return consumer -> parts.forEachPart((i, m) -> {
            if (appearance.partVisible(i)) consumer.accept(i, m);
        });
    }

    private void drawFlat(ModelGpuMesh mesh, ModelDrawPlan plan, PartMatrices parts, JointPalette skinPalette,
                          Matrix4f view, Matrix4f projection, Vector4f color, ModelAppearance appearance) {
        if (!initialized || flatShader == null || !drawable(mesh, plan)) return;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean wasCullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDisable(GL11.GL_CULL_FACE);

        drawUntextured(mesh, plan, parts, skinPalette, view, projection, color, appearance);

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
        drawWireframe(mesh, plan, parts, null, view, projection, color, null);
    }

    /** Wireframe overlay posed by a native rig's palette (see the lit overload). */
    public void drawWireframe(ModelGpuMesh mesh, ModelDrawPlan plan, JointPalette palette,
                              Matrix4f view, Matrix4f projection, Vector4f color) {
        drawWireframe(mesh, plan, palette, view, projection, color, null);
    }

    /** Wireframe overlay posed by a palette with an appearance (hidden parts / groups, morph weights). */
    public void drawWireframe(ModelGpuMesh mesh, ModelDrawPlan plan, JointPalette palette,
                              Matrix4f view, Matrix4f projection, Vector4f color, ModelAppearance appearance) {
        PartMatrices parts = mesh != null && mesh.hasSkin() ? everyPart(plan) : rigidParts(plan, palette);
        if (appearance != null) parts = visibleOnly(parts, appearance);
        drawWireframe(mesh, plan, parts, mesh != null && mesh.hasSkin() ? palette : null, view, projection, color,
                appearance);
    }

    private void drawWireframe(ModelGpuMesh mesh, ModelDrawPlan plan, PartMatrices parts, JointPalette skinPalette,
                               Matrix4f view, Matrix4f projection, Vector4f color, ModelAppearance appearance) {
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

        drawUntextured(mesh, plan, parts, skinPalette, view, projection, color, appearance);

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

    private void drawUntextured(ModelGpuMesh mesh, ModelDrawPlan plan, PartMatrices parts, JointPalette skinPalette,
                                Matrix4f view, Matrix4f projection, Vector4f color, ModelAppearance appearance) {
        ShaderProgram shader = flatShader;
        ModelDrawPlan drawPlan = plan.forAppearance(appearance);
        shader.bind();
        shader.setUniform("view", view);
        shader.setUniform("projection", projection);
        shader.setUniform("color", color);
        beginSkin(shader, skinPalette, mesh);
        boolean morphed = mesh.beginMorphs(shader, appearance != null ? appearance.morphWeights() : null);

        GL30.glBindVertexArray(mesh.vao());
        parts.forEachPart((partIndex, partMatrix) -> {
            shader.setUniform("model", partMatrix);
            for (IndexedDrawBatch batch : drawPlan.forPart(partIndex).untextured()) {
                GL11.glDrawElements(GL11.GL_TRIANGLES, batch.indexCount(),
                        GL11.GL_UNSIGNED_INT, batch.byteOffset());
            }
        });
        if (morphed) ModelGpuMesh.endMorphs(shader);
        endSkin(shader, skinPalette);
        GL30.glBindVertexArray(0);
        shader.unbind();
    }

    private void beginSkin(ShaderProgram shader, JointPalette skinPalette, ModelGpuMesh mesh) {
        if (skinPalette == null) return;
        paletteTexture.upload(skinPalette);
        paletteTexture.bind(JointPaletteTexture.DEFAULT_UNIT);
        mesh.bindSkinInfluences(ModelSkinGlsl.INFLUENCES_UNIT);
        shader.setUniform("u_skinned", true);
    }

    private static void endSkin(ShaderProgram shader, JointPalette skinPalette) {
        if (skinPalette == null) return;
        shader.setUniform("u_skinned", false);
        JointPaletteTexture.unbind(JointPaletteTexture.DEFAULT_UNIT);
        JointPaletteTexture.unbind(ModelSkinGlsl.INFLUENCES_UNIT);
    }

    /** Every part of the plan drawn once with the identity (the palette moves the vertices). */
    private static PartMatrices everyPart(ModelDrawPlan plan) {
        return consumer -> {
            for (int i = 0; i < plan.partCount(); i++) consumer.accept(i, IDENTITY);
        };
    }

    /** Each part rigid with its joint's skin matrix (part i = joint i). */
    private static PartMatrices rigidParts(ModelDrawPlan plan, JointPalette palette) {
        return consumer -> {
            Matrix4f m = new Matrix4f();
            int n = Math.min(plan.partCount(), palette.count());
            for (int i = 0; i < n; i++) consumer.accept(i, palette.skinMatrix(i, m));
        };
    }

    private static boolean drawable(ModelGpuMesh mesh, ModelDrawPlan plan) {
        return mesh != null && plan != null && mesh.isCreated() && mesh.indexCount() > 0;
    }

    /** Release the shaders (meshes are owned by their callers). */
    public void cleanup() {
        if (!initialized) return;
        paletteTexture.close();
        if (litShader != null) litShader.cleanup();
        if (flatShader != null) flatShader.cleanup();
        litShader = null;
        flatShader = null;
        initialized = false;
    }

    // ---- Draw capture (OpenMason #175 F4) -----------------------------------------------------------------------

    private ModelGpuMesh captureMesh;
    private final DrawCapture capture = new DrawCapture();

    /**
     * Remembers what the lit pass draws of {@code mesh} (null stops capturing): an ID / picking pass replays it
     * ({@link #capturedDraw}) so a pick sees exactly what is on screen - hidden parts and vertex groups, the skin
     * palette, shape keys (the camera is the replaying pass's own). Costs one reference compare per draw of any other
     * mesh.
     */
    public void captureDrawsOf(ModelGpuMesh mesh) {
        if (mesh != captureMesh) {
            captureMesh = mesh;
            capture.clear();
        }
    }

    /** The last lit draw of the {@link #captureDrawsOf captured} mesh, or null before its first draw. */
    public DrawCapture capturedDraw() {
        return capture.mesh != null && capture.mesh == captureMesh ? capture : null;
    }

    /**
     * What one lit draw drew. The objects are the draw's own (the plan, the part matrices, the caller's palette that
     * its next draw refills): replay it before the next draw of the mesh. {@link #serial} counts captures.
     */
    public static final class DrawCapture {
        private ModelGpuMesh mesh;
        private ModelDrawPlan plan;
        private ModelDrawPlan basePlan;
        private PartMatrices parts;
        private MaterialTextures textures;
        private JointPalette skinPalette;
        private float[] morphWeights;
        private long serial;

        void set(ModelGpuMesh mesh, ModelDrawPlan plan, ModelAppearance appearance, PartMatrices parts,
                 MaterialTextures textures, JointPalette skinPalette) {
            this.mesh = mesh;
            this.basePlan = plan;
            this.plan = plan.forAppearance(appearance);
            this.parts = appearance != null ? visibleOnly(parts, appearance) : parts;
            this.textures = textures;
            this.skinPalette = skinPalette;
            this.morphWeights = appearance != null ? appearance.morphWeights() : null;
            serial++;
        }

        void clear() {
            mesh = null;
            plan = null;
            basePlan = null;
            parts = null;
            textures = null;
            skinPalette = null;
            morphWeights = null;
        }

        public ModelGpuMesh mesh() { return mesh; }
        /** The plan as drawn (cut down to the appearance's visible vertex groups). */
        public ModelDrawPlan plan() { return plan; }
        /** The plan the draw was given, before the appearance's vertex-group mask (an editor's edit cage). */
        public ModelDrawPlan basePlan() { return basePlan; }
        /** The parts the draw issued, with their {@code model} matrices (hidden parts left out). */
        public PartMatrices parts() { return parts; }
        /** The lit pass's textures: a batch whose texture is negative was not drawn. */
        public MaterialTextures textures() { return textures; }
        /** The skin palette the mesh was GPU-skinned with, or null (rigid parts). */
        public JointPalette skinPalette() { return skinPalette; }
        /** The shape-key weights (null = the mesh's defaults). */
        public float[] morphWeights() { return morphWeights; }
        public long serial() { return serial; }
    }
}
