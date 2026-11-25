package com.openmason.main.systems.character.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.mcp.McpSchema;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optString;

/**
 * The {@code character} MCP group (OpenMason #163): characterize a model into an {@code .omchar} through the
 * character open in the Character tab. Every editing tool is one undo step (char_undo). Guide: describe_api
 * topic:"character".
 */
public final class CharacterToolDefinitions {

    private final CharacterEditingService service;
    private final ObjectMapper mapper;

    public CharacterToolDefinitions(CharacterEditingService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool("char_open",
                "Open an .omchar ('path') or start a character for a model file ('model': .omo or a plugin's model container) in the "
                        + "Character tab. Guide: describe_api topic:'character'.",
                schema().str("path", "An .omchar").str("model", "A model to characterize")
                        .bool("discard", "Drop unsaved changes of the open character").build(),
                args -> service.open(optString(args, "path"), optString(args, "model"), optBool(args, "discard", false))));

        registry.register(new McpTool("char_get",
                "The open character, or one section: roles, joints, pose, limits, chains, goals, sockets, metrics, "
                        + "issues, profile.",
                schema().str("section", "One section only").build(),
                args -> service.get(optString(args, "section"))));

        registry.register(new McpTool("char_auto_map",
                "Map roles from the skeleton onto a profile (default: the character's; humanoid, quadruped, bird or "
                        + "custom): roles, facing, chains, IK goals.",
                schema().str("profile", "Profile id").build(),
                args -> service.autoMap(optString(args, "profile"))));

        registry.register(new McpTool("char_edit",
                "Edit atomically (one undo step). ops: role, pose, limit, socket, standard_socket, chain, goal, "
                        + "metrics, compute_metrics, field (see the guide); joints by id, name or role:<r>.",
                schema().arr("ops", "object", "Operations {op, ...}").str("label", "Undo label").required("ops").build(),
                args -> service.edit(args.get("ops"), optString(args, "label"))));

        registry.register(new McpTool("char_mirror",
                "Copy one side onto the other by geometry: roles, limits, sockets.",
                schema().enumStr("from", "Source side", "left", "right").strArray("what", "roles, limits, sockets (default all)")
                        .build(),
                args -> service.mirror(optString(args, "from"), strings(args.get("what")))));

        registry.register(new McpTool("char_reference_pose",
                "Fit a profile pose ('fit': T, A...) and/or set rotations {joint: [x,y,z] | null}; clear:true first.",
                schema().str("fit", "Profile pose").obj("rotations", "Joint -> Euler XYZ degrees").bool("clear", "Clear first")
                        .build(),
                args -> service.referencePose(optString(args, "fit"), args.get("rotations"), optBool(args, "clear", false))));

        registry.register(new McpTool("char_sockets",
                "Place standard sockets at their default spots (names or \"all\": grip_r, grip_l, head, eyes, back); "
                        + "lists the sockets.",
                schema().strArray("place", "Standard socket names, or [\"all\"]").build(),
                args -> service.sockets(strings(args.get("place")))));

        registry.register(new McpTool("char_validate",
                "Validate against the skeleton and the profile: issues, unmapped required roles, lock state.",
                schema().build(),
                args -> service.validate()));

        registry.register(new McpTool("char_lock",
                "Lock the role mapping (refused while validation has errors), or unlock:true.",
                schema().bool("unlock", "Unlock instead").build(),
                args -> service.lock(optBool(args, "unlock", false))));

        registry.register(new McpTool("char_undo",
                "Undo (or redo:true) the last character edit (tab and tools share one history).",
                schema().bool("redo", "Redo instead").build(),
                args -> service.undo(optBool(args, "redo", false))));

        registry.register(new McpTool("char_save",
                "Write the .omchar: in place, or 'path' (a bare name lands in the project's Characters folder).",
                schema().str("path", "Target (absolute or project:/target:/exports: path)")
                        .bool("prompt", "Let the user pick").bool("overwrite", "Replace an existing file").build(),
                args -> service.save(optString(args, "path"), optBool(args, "prompt", false), optBool(args, "overwrite", false))));

        registry.register(new McpTool("char_close",
                "Close the open character (discard:true drops unsaved changes).",
                schema().bool("discard", "Drop unsaved changes").build(),
                args -> service.close(optBool(args, "discard", false))));
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private static List<String> strings(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        } else {
            out.add(n.asText());
        }
        return out;
    }
}
