package com.openmason.engine.format.ext;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ManifestJson}'s raw member capture (the engine-only half of #383's extension tests, #88). */
class ManifestJsonTest {

    @Test
    void rawMembersCaptureEveryValueKind() {
        Map<String, String> m = ManifestJson.rawMembers(
                "{\"s\": \"a \\\"q\\\" b\", \"n\" : -1.5e3, \"t\":true, \"z\" :null, \"o\":{\"x\":[1,{}]}, \"a\":[ ]}");
        assertEquals("\"a \\\"q\\\" b\"", m.get("s"));
        assertEquals("-1.5e3", m.get("n"));
        assertEquals("true", m.get("t"));
        assertEquals("null", m.get("z"));
        assertEquals("{\"x\":[1,{}]}", m.get("o"));
        assertEquals("[ ]", m.get("a"));
    }
}
