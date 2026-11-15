package com.openmason.api.asset;

import java.util.Objects;

/**
 * An entry of the Texture Editor's Tools menu an asset kind brings (#88): "Export &lt;container&gt;...",
 * "&lt;kind&gt; Editor...". Entries are sorted by {@code order}.
 *
 * @param id              stable id, unique within its contribution
 * @param label           menu text
 * @param order           position in the menu
 * @param needsTexture    enabled only while the editor holds a saved {@code .omt} ({@link TextureSource})
 * @param separatorBefore draw a separator above the entry
 * @param action          runs on the UI thread when clicked
 */
public record TextureAction(String id, String label, int order, boolean needsTexture, boolean separatorBefore,
                            Runnable action) {

    public TextureAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(action, "action");
    }
}
