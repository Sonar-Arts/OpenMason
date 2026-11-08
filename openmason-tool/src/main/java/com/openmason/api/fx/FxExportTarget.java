package com.openmason.api.fx;

import com.openmason.api.files.FileType;

import java.util.Objects;

/**
 * A container FX systems can be exported to, contributed through {@code PluginContext.fxExporters()} (#102, the
 * FX side of #88: game formats come from game plugins). The Particles workspace and the {@code fx_export} MCP tool
 * list every registered target.
 *
 * <p>Where files go comes from {@link #fileType}'s save rule: a rule into the {@code target} root with an asset
 * folder key ({@code withSave("target", "mygamefx", "fx")}, the game target mapping {@code mygamefx} to its folder) makes the
 * target <em>deployable</em> — written straight into the active game target's checkout, as the SBO/SBE editors do.
 * Any other rule is an ordinary export ({@code project}/{@code exports} roots, or a file the user picks).
 *
 * @param id           unique id ({@code <plugin>.<format>})
 * @param displayName  name shown in the Export FX window ("My Game FX (.mgfx)")
 * @param fileType     the container's file type (extension, save rule); needs a save rule
 * @param gameTargetId the game target the container belongs to (deploy only while it is active), or null for any
 * @param exporter     writes the file
 */
public record FxExportTarget(String id, String displayName, FileType fileType, String gameTargetId,
                             FxExporter exporter) {

    public FxExportTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fileType, "fileType");
        Objects.requireNonNull(exporter, "exporter");
        if (id.isBlank()) {
            throw new IllegalArgumentException("FX export target id is blank");
        }
        if (fileType.save() == null) {
            throw new IllegalArgumentException("FX export target " + id + ": file type " + fileType.id()
                    + " needs a save rule (where exported files go)");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = fileType.displayName() + " (" + fileType.extension() + ")";
        }
        if (gameTargetId != null && gameTargetId.isBlank()) {
            gameTargetId = null;
        }
    }

    /** True when files go into the game target's checkout (the save rule's root is {@code target}). */
    public boolean deploys() {
        String root = fileType.save().root().toLowerCase(java.util.Locale.ROOT);
        return root.equals("target") || root.equals("game") || root.equals("game_resources");
    }
}
