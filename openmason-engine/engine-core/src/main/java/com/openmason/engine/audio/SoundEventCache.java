package com.openmason.engine.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily resolved {@link SoundEvents} per asset key (a block type, an entity id, an FX system):
 * the first play of a key resolves and loads its samples, later plays reuse them. A resolver
 * that is not ready yet (registry still loading, audio not up) returns null, which plays as
 * {@link SoundEvents#EMPTY} without being cached, so the key is tried again next time.
 * Safe from any thread.
 */
public final class SoundEventCache<K> {

    /** Resolves one key; null = not ready, try again later. */
    @FunctionalInterface
    public interface Resolver<K> {
        SoundEvents resolve(K key);
    }

    private final Map<K, SoundEvents> cache = new ConcurrentHashMap<>();
    private final Resolver<K> resolver;

    public SoundEventCache(Resolver<K> resolver) {
        this.resolver = resolver;
    }

    /** The key's events; never null. */
    public SoundEvents get(K key) {
        if (key == null) return SoundEvents.EMPTY;
        SoundEvents cached = cache.get(key);
        if (cached != null) return cached;
        SoundEvents resolved = resolver.resolve(key);
        if (resolved == null) return SoundEvents.EMPTY;
        SoundEvents raced = cache.putIfAbsent(key, resolved);
        return raced != null ? raced : resolved;
    }

    /** Forgets every resolution (e.g. after the asset registry reloads). */
    public void invalidate() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }
}
