package com.openmason.engine.entity;

/**
 * Which entities a query accepts ({@code ce_filter}).
 *
 * @param typeId       the only type accepted, or -1 for any
 * @param requireFlags body flags that must all be set
 * @param excludeFlags body flags none of which may be set
 * @param exclude      an entity handle never answered (0 = none)
 */
public record EntityFilter(int typeId, int requireFlags, int excludeFlags, long exclude) {

    /** Accepts every entity. */
    public static final EntityFilter ANY = new EntityFilter(-1, 0, 0, 0L);

    public static EntityFilter ofType(int typeId) {
        return new EntityFilter(typeId, 0, 0, 0L);
    }

    public static EntityFilter requiring(int requireFlags) {
        return new EntityFilter(-1, requireFlags, 0, 0L);
    }

    public EntityFilter withType(int type) {
        return new EntityFilter(type, requireFlags, excludeFlags, exclude);
    }

    public EntityFilter withExclude(long handle) {
        return new EntityFilter(typeId, requireFlags, excludeFlags, handle);
    }
}
