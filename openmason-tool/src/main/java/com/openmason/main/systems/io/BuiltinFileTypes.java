package com.openmason.main.systems.io;

import com.openmason.api.files.FileType;

import java.util.List;

/**
 * The file types the built-in plugins register (#379). Constants so tool code can name a kind
 * ({@code WriteKind.OMO}); the registry is still the source of truth for what the editor knows
 * (a plugin adds its own types next to these).
 */
public final class BuiltinFileTypes {

    private BuiltinFileTypes() {
    }

    // ── openmason.core ──────────────────────────────────────────────────────

    public static final FileType OMO = FileType.of("omo", "Open Mason Model", ".omo")
            .withSave("project", "", "model")
            .withBrowse(".OMO Model", 0, "");

    public static final FileType OMSC = FileType.of("omsc", "Open Mason Scene", ".omsc")
            .withSave("project", "Scenes", "scene")
            .withBrowse(".OMSC Scene", 2, "", "Scenes");

    public static final FileType OMP = FileType.of("omp", "Open Mason Project", ".omp");

    public static final FileType PNG = FileType.of("png", "PNG Image", ".png")
            .withSave("exports", "", "texture");

    // ── openmason.texture ───────────────────────────────────────────────────

    public static final FileType OMT = FileType.of("omt", "Open Mason Texture", ".omt")
            .withSave("project", "", "texture")
            .withBrowse(".OMT Texture", 1, "");

    // ── openmason.animation ─────────────────────────────────────────────────

    public static final FileType OMANIM = FileType.of("omanim", "Open Mason Animation", ".omanim")
            .withSave("project", "", "animation");

    // ── openmason.particles ─────────────────────────────────────────────────

    /** Particle effect libraries (engine {@code ParticleLibrary} JSON), edited in Tools -> Particle Editor. */
    public static final FileType OMFX = FileType.of("omfx", "Particle Library", ".omfx")
            .withSave("project", "Particles", "particles");

    // ── openmason.character ─────────────────────────────────────────────────

    /** Character definitions (OM #163, native .omchar), edited in the Character tab. */
    public static final FileType OMCHAR = FileType.of("omchar", "Character", ".omchar")
            .withSave("project", "Characters", "character");

    /** Animation graphs (OM #157/#164, native .omanimgraph), edited in the Animation Graph tab. */
    public static final FileType OMANIMGRAPH = FileType.of("omanimgraph", "Animation Graph", ".omanimgraph")
            .withSave("project", "AnimGraphs", "graph");

    // ── openmason.ui ────────────────────────────────────────────────────────

    /** UI Editor source documents (#324); bare names land beside the convention tree {@code UI/}. */
    public static final FileType OMUI = FileType.of("omui", "UI Document", ".omui")
            .withSave("project", "UI", "screen")
            .withBrowse(".OMUI UI Document", 3, "", "UI/**");

    /** Exported UI documents, the editor's default export folder. */
    public static final FileType SBUI = FileType.of("sbui", "Exported UI Document", ".sbui")
            .withSave("project", "Exports/UI", "screen");

    /** Every built-in type, in the order the built-in plugins register them. */
    public static List<FileType> all() {
        return List.of(OMO, OMSC, OMP, PNG, OMT, OMANIM, OMUI, SBUI);
    }
}
