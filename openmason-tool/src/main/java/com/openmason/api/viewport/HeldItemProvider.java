package com.openmason.api.viewport;

import org.joml.Matrix4fc;

import java.util.List;

/**
 * Items a character can hold in the Animation and Animation Graph previews (normal and Eyes view): a game says which
 * items it has, where each one hangs (a character socket + the offset from the socket frame to the item's own draw
 * space - the game's grip data) and draws one. The editor resolves the socket on whatever pose it shows - the Eyes
 * view's first-person pose included - so a held item can be checked exactly as the game will place it.
 */
public interface HeldItemProvider {

    /** One holdable item: a stable id (the provider's own) and a display name. */
    record Item(String id, String label) {}

    /**
     * Where an item hangs: the character socket name and {@code offset}, socket frame to the item's draw space
     * (what {@link #render} draws in).
     */
    record Mount(String socket, Matrix4fc offset) {}

    /** Unique provider id. */
    String id();

    /** Human-readable name. */
    String label();

    /** The holdable items, in display order. */
    List<Item> items();

    /** Where {@code itemId} hangs; null when the provider does not know it. */
    Mount mount(String itemId);

    /**
     * Draws {@code itemId} with {@code model} (its draw space to world), {@code view} and {@code projection}. GL
     * thread, inside the preview's frame buffer (depth test on); must restore the GL state it changes.
     */
    void render(String itemId, Matrix4fc model, Matrix4fc view, Matrix4fc projection);
}
