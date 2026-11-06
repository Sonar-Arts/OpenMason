package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.optStringList;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * MCP tools for the Texture Creator's saved palettes (issue #94): list, create,
 * switch, edit and delete the palettes shown in the color panel and palette
 * strip. Palettes are editor-wide (not per document) and persist immediately;
 * edits are not on the canvas undo stack, same as in the UI.
 */
public final class PaletteToolDefinitions {

    private static final String COLOR_FORMAT = "'#rrggbb', '#rrggbbaa' or 'r,g,b[,a]'";

    private final PaletteEditingService palettes;
    private final ObjectMapper mapper;

    public PaletteToolDefinitions(PaletteEditingService palettes, ObjectMapper mapper) {
        this.palettes = palettes;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "tex_palette_list",
                "List the texture editor's saved palettes (the user's swatches in the color panel / "
                        + "palette strip): each palette's name and swatches as hex (#rrggbb[aa]), plus the "
                        + "active one. Reuse these colours in tex_* calls to match the user's palette.",
                schema().build(),
                args -> palettes.list()));

        registry.register(new McpTool(
                "tex_palette_create",
                "Create a named palette and make it active (shown in the UI live). 'colors' seeds it "
                        + "(" + COLOR_FORMAT + ", duplicates dropped; [] = empty); omit for the DB32 default. "
                        + "Fails if the name exists.",
                schema()
                        .str("name", "Palette name")
                        .strArray("colors", "Initial swatches; omit for DB32")
                        .required("name")
                        .build(),
                args -> palettes.create(reqString(args, "name"),
                        args.hasNonNull("colors") ? optStringList(args, "colors") : null)));

        registry.register(new McpTool(
                "tex_palette_switch",
                "Make the named palette the active one in the color panel / palette strip.",
                schema().str("name", "Palette name").required("name").build(),
                args -> palettes.switchTo(reqString(args, "name"))));

        registry.register(new McpTool(
                "tex_palette_add_swatches",
                "Append colours (" + COLOR_FORMAT + ") to the active palette; colours already in it "
                        + "are skipped and reported.",
                schema().strArray("colors", "Swatches to append").required("colors").build(),
                args -> palettes.addSwatches(optStringList(args, "colors"))));

        registry.register(new McpTool(
                "tex_palette_remove_swatch",
                "Remove a swatch from the active palette: by 'index', or every swatch equal to 'color'.",
                schema()
                        .intg("index", "Swatch index (see tex_palette_list)")
                        .str("color", "Colour to remove (" + COLOR_FORMAT + ")")
                        .build(),
                args -> palettes.removeSwatch(
                        args.hasNonNull("index") ? McpArgs.reqInt(args, "index") : null,
                        optString(args, "color"))));

        registry.register(new McpTool(
                "tex_palette_delete",
                "Delete a palette (default: the active one; the first remaining becomes active). "
                        + "The last palette can't be deleted.",
                schema().str("name", "Palette name; omit for the active one").build(),
                args -> palettes.delete(optString(args, "name"))));
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }
}
