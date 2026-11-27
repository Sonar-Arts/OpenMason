package com.openmason.main.systems.skeleton.vertexdata;

import com.openmason.engine.format.omo.OMOFormat;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the editor PREVIEWS of the shape keys and vertex groups (not saved, not undone): the slider value of each
 * shape key (by name; absent = the key's default) and the vertex groups hidden in the preview. Written by the
 * rigging pane's Shape Keys / Vertex Groups sections and the {@code morph_set value} MCP member.
 *
 * <p><b>Consumer contract (scene viewport, after the GPU-skinning merge):</b> a renderer that draws the live model
 * through an engine {@code ModelGpuMesh} / {@code ModelAppearance} reads, whenever {@link #revision()} changed:
 * <ul>
 *   <li>{@link VertexDataAuthoring#soupMorphs} - the shape keys as a native {@code RigMorphs} over the editor's
 *       COMBINED soup ({@code MeshRange.vertexStart + part-local vertex}); upload with
 *       {@code ModelGpuMesh.updateMorphs} when {@link VertexDataAuthoring#dataRevision()} changed;</li>
 *   <li>{@link #weights(List)} with {@code shapeKeys().morphs()} - the raw weights in target order, for
 *       {@code ModelAppearance.setMorphWeights} (the GPU path clamps them to each key's range);</li>
 *   <li>{@link #hiddenGroups()} - for {@code ModelAppearance.setHiddenGroups} (needs a mask plan built from
 *       {@link VertexDataAuthoring#groups()}, see {@code EntityModelLoader.maskPlan}).</li>
 * </ul>
 * Today (before that merge) the Animation tab's viewport previews keyed shape-key weights of the saved model, and
 * this object only carries the editor's choices. Main thread only.
 */
public final class VertexDataPreview {

    private final Map<String, Float> values = new HashMap<>();
    private final Set<String> hiddenGroups = new LinkedHashSet<>();
    private long revision;

    /** Bumped by every change; consumers poll it. */
    public long revision() {
        return revision;
    }

    /** The preview value of a shape key (its default when none was set). */
    public float value(OMOFormat.MorphData key) {
        Float v = values.get(key.name());
        return v != null ? v : key.defaultWeight();
    }

    /** True when the key's preview value was set (differs from following its default). */
    public boolean hasValue(String name) {
        return values.containsKey(name);
    }

    public void setValue(String name, float value) {
        if (name == null || !Float.isFinite(value)) return;
        Float prev = values.put(name, value);
        if (prev == null || prev != value) revision++;
    }

    public void clearValue(String name) {
        if (values.remove(name) != null) revision++;
    }

    /** Back to every default. */
    public void resetValues() {
        if (!values.isEmpty()) {
            values.clear();
            revision++;
        }
    }

    /** Raw preview weights in the order of {@code keys} (one per target), for {@code ModelAppearance}. */
    public float[] weights(List<OMOFormat.MorphData> keys) {
        float[] out = new float[keys.size()];
        for (int i = 0; i < out.length; i++) out[i] = value(keys.get(i));
        return out;
    }

    public boolean isHidden(String group) {
        return hiddenGroups.contains(group);
    }

    public void setHidden(String group, boolean hidden) {
        if (group == null) return;
        boolean changed = hidden ? hiddenGroups.add(group) : hiddenGroups.remove(group);
        if (changed) revision++;
    }

    /** The groups hidden in the preview (a copy). */
    public Set<String> hiddenGroups() {
        return Set.copyOf(hiddenGroups);
    }

    /** What the preview holds for a name: its set value (or null) and whether it is hidden. */
    public record Choice(Float value, boolean hidden) {}

    public Choice choice(String name) {
        return new Choice(values.get(name), hiddenGroups.contains(name));
    }

    /** Gives {@code name} a choice taken before a rename (the old name may already be forgotten). */
    public void apply(String name, Choice choice) {
        if (name == null || choice == null) return;
        if (choice.value() != null) values.put(name, choice.value());
        if (choice.hidden()) hiddenGroups.add(name);
        revision++;
    }

    /** Forgets choices for names that no longer exist (after a load, undo or removal). */
    public void retain(Set<String> shapeKeys, Set<String> groups) {
        boolean changed = values.keySet().retainAll(shapeKeys);
        changed |= hiddenGroups.retainAll(groups);
        if (changed) revision++;
    }

    public void clear() {
        if (!values.isEmpty() || !hiddenGroups.isEmpty()) revision++;
        values.clear();
        hiddenGroups.clear();
    }
}
