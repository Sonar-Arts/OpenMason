package com.openmason.main.systems.menus.panes.projectBrowser.thumbnails;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #175 F1: a thumbnail that cannot be made (a model without a texture) is cached as a miss for its file version,
 * so the browser does not re-read and decode the whole file every frame; a new version retries. GL-free (a miss
 * holds no texture).
 */
class ThumbnailCacheMissTest {

    @Test
    void aMissIsCachedUntilTheFileChanges() {
        ThumbnailCache cache = new ThumbnailCache();
        AtomicInteger generated = new AtomicInteger();
        ThumbnailCache.ThumbnailGenerator none = () -> {
            generated.incrementAndGet();
            return 0;
        };
        for (int frame = 0; frame < 10; frame++) {
            assertEquals(0, cache.getOrCreate("omo_a_64", 1000L, none));
        }
        assertEquals(1, generated.get(), "one decode for the version");

        assertEquals(0, cache.getOrCreate("omo_a_64", 2000L, none));
        assertEquals(2, generated.get(), "a re-saved file retries");
    }
}
