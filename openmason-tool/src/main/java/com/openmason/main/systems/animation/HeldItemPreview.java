package com.openmason.main.systems.animation;

import com.openmason.api.viewport.HeldItemProvider;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntitySockets;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.SocketSet;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A held item in the Animation / Animation Graph previews (normal and Eyes view): the item a game's
 * {@link HeldItemProvider} names, on the character socket its mount names, resolved on the pose the preview draws
 * (the Eyes view's lens-pulled first-person pose included, so it lands where the game draws it) and drawn by the
 * provider. Sockets = the model's attachment points + its character's sockets ({@link EntitySockets#build}). GL
 * thread.
 */
public final class HeldItemPreview implements AutoCloseable {

    private final Supplier<List<HeldItemProvider>> providers;
    private String providerId;
    private String itemId;
    private SocketSet sockets;
    private EntityGeometry socketsGeometry;
    private CharacterDefinition socketsCharacter;
    private String problem;
    private final Matrix4f frame = new Matrix4f();

    public HeldItemPreview(Supplier<List<HeldItemProvider>> providers) {
        this.providers = providers == null ? List::of : providers;
    }

    /** Every registered provider (empty when no game contributes held items). */
    public List<HeldItemProvider> providers() {
        List<HeldItemProvider> all = providers.get();
        return all == null ? List.of() : all;
    }

    /**
     * Holds {@code item}: "providerId:itemId", or a bare item id the first provider that knows it resolves; null or
     * blank = empty hands. Throws when no provider knows the item.
     */
    public void select(String item) {
        if (item == null || item.isBlank()) {
            providerId = null;
            itemId = null;
            problem = null;
            return;
        }
        int colon = item.indexOf(':');
        for (HeldItemProvider p : providers()) {
            String id = colon > 0 && item.substring(0, colon).equals(p.id()) ? item.substring(colon + 1) : item;
            if (p.mount(id) != null) {
                providerId = p.id();
                itemId = id;
                problem = null;
                return;
            }
        }
        throw new IllegalArgumentException("no held item provider knows '" + item + "'"
                + (providers().isEmpty() ? " (no game plugin contributes held items)" : ""));
    }

    public boolean active() {
        return itemId != null;
    }

    /** The held item's "providerId:itemId", or null. */
    public String selected() {
        return itemId == null ? null : providerId + ":" + itemId;
    }

    /** The held item's display name, or null. */
    public String selectedLabel() {
        HeldItemProvider p = provider();
        if (p == null) return null;
        for (HeldItemProvider.Item i : p.items()) {
            if (i.id().equals(itemId)) return i.label();
        }
        return itemId;
    }

    private HeldItemProvider provider() {
        if (itemId == null) return null;
        for (HeldItemProvider p : providers()) {
            if (p.id().equals(providerId)) return p;
        }
        return null;
    }

    /**
     * Draws the held item on {@code palette} (the pose drawn, model space under the palette's own base) with the
     * preview's {@code view} / {@code projection}. Nothing without an item; {@link #problem()} says why it could not.
     */
    public void draw(EntityGeometry geometry, CharacterDefinition character, JointPalette palette, Matrix4fc view,
                     Matrix4fc projection) {
        problem = null;
        HeldItemProvider p = provider();
        if (p == null || geometry == null || palette == null) {
            if (itemId != null && p == null) problem = "its provider is gone";
            return;
        }
        try {
            HeldItemProvider.Mount mount = p.mount(itemId);
            if (mount == null) {
                problem = "unknown item";
                return;
            }
            SocketSet set = socketsFor(geometry, character);
            int index = set == null ? -1 : set.find(mount.socket());
            if (index < 0) {
                problem = "the model has no socket '" + mount.socket() + "'";
                return;
            }
            if (set.frame(index, palette, null, null, frame) == null) {
                problem = "socket '" + mount.socket() + "' is off the pose";
                return;
            }
            p.render(itemId, frame.mul(mount.offset()), view, projection);
        } catch (RuntimeException e) {
            problem = e.getMessage();
        }
    }

    private SocketSet socketsFor(EntityGeometry geometry, CharacterDefinition character) {
        if (sockets != null && socketsGeometry == geometry && socketsCharacter == character) return sockets;
        if (sockets != null) sockets.close();
        sockets = EntitySockets.build(geometry, character);
        socketsGeometry = geometry;
        socketsCharacter = character;
        return sockets;
    }

    /** The "Held" combo of a preview's toggle row: none + every provider's items (provider-grouped when several). */
    public void renderCombo(String id) {
        List<HeldItemProvider> all = providers();
        if (all.isEmpty()) return;
        String current = active() ? selectedLabel() : "(empty hands)";
        imgui.ImGui.setNextItemWidth(170f);
        if (imgui.ImGui.beginCombo("Held##" + id, current)) {
            if (imgui.ImGui.selectable("(empty hands)", !active())) select(null);
            for (HeldItemProvider p : all) {
                if (all.size() > 1) imgui.ImGui.separatorText(p.label());
                for (HeldItemProvider.Item item : p.items()) {
                    boolean on = active() && p.id().equals(providerId) && item.id().equals(itemId);
                    if (imgui.ImGui.selectable(item.label() + "##" + p.id() + ":" + item.id(), on)) {
                        select(p.id() + ":" + item.id());
                    }
                }
            }
            imgui.ImGui.endCombo();
        }
        if (problem != null && imgui.ImGui.isItemHovered()) imgui.ImGui.setTooltip("Held: " + problem);
    }

    /** Why the last draw drew nothing although an item is held (null otherwise). */
    public String problem() {
        return problem;
    }

    /** The state MCP reports: item, label, socket, problem. */
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", selected());
        m.put("label", selectedLabel());
        HeldItemProvider p = provider();
        HeldItemProvider.Mount mount = p == null ? null : p.mount(itemId);
        m.put("socket", mount == null ? null : mount.socket());
        m.put("problem", problem);
        return m;
    }

    @Override
    public void close() {
        if (sockets != null) sockets.close();
        sockets = null;
    }
}
