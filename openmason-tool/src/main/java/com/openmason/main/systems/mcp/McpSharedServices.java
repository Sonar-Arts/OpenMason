package com.openmason.main.systems.mcp;

import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.services.EditorServices;

/**
 * The MCP domain services more than one tool group uses (the model file service is shared by the
 * asset exporter; the editing services by their own group and by {@code undo}/{@code redo}
 * routing), created once per registry build so every group sees the same instances.
 */
public record McpSharedServices(EditorServices editor, AssetWriteService writes, ModelFileService modelFiles,
                                ModelEditingService model, TextureEditingService texture,
                                FaceTextureEditingService faceTexture, BoneEditingService bones,
                                SkinEditingService skin, AttachmentEditingService attachments,
                                com.openmason.main.systems.animation.mcp.AnimationEditingService animation) {

    public static McpSharedServices create(EditorServices editor, AssetWriteService writes) {
        return new McpSharedServices(editor, writes, new ModelFileService(editor, writes),
                new ModelEditingService(editor), new TextureEditingService(editor),
                new FaceTextureEditingService(editor), new BoneEditingService(editor),
                new SkinEditingService(editor), new AttachmentEditingService(editor),
                new com.openmason.main.systems.animation.mcp.AnimationEditingService(() -> editor == null ? null
                        : editor.registry().find(com.openmason.main.systems.animation.AnimationSessions.class).orElse(null),
                        writes));
    }

    /** The shared services of a build. */
    public static McpSharedServices of(com.openmason.api.mcp.McpToolContext context) {
        return context.services().require(McpSharedServices.class);
    }
}
