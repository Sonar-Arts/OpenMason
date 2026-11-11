package com.openmason.engine.audio.emitters;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

/**
 * Owns the world's sound emitters: updates, adds, removes and lists them for debug views.
 * The host calls {@link #update(float)} only while a world is running. Not thread-safe.
 */
public class SoundEmitterManager {
    private final List<SoundEmitter> emitters;

    public SoundEmitterManager() {
        this.emitters = new ArrayList<>();
    }

    /**
     * Updates all active sound emitters.
     * @param deltaTime The time elapsed since last update in seconds
     */
    public void update(float deltaTime) {
        for (SoundEmitter emitter : emitters) {
            emitter.update(deltaTime);
        }
    }

    /**
     * Adds a sound emitter to the manager.
     * @param emitter The sound emitter to add
     */
    public void addEmitter(SoundEmitter emitter) {
        if (emitter != null) {
            emitters.add(emitter);
        }
    }

    /**
     * Removes a sound emitter from the manager.
     * @param emitter The sound emitter to remove
     * @return True if the emitter was found and removed
     */
    public boolean removeEmitter(SoundEmitter emitter) {
        return emitters.remove(emitter);
    }

    /**
     * Removes all sound emitters within a certain distance of a position.
     * @param position The center position
     * @param radius The radius around the position
     * @return The number of emitters removed
     */
    public int removeEmittersInRadius(Vector3f position, float radius) {
        Iterator<SoundEmitter> iterator = emitters.iterator();
        int removedCount = 0;
        float radiusSquared = radius * radius;

        while (iterator.hasNext()) {
            SoundEmitter emitter = iterator.next();
            Vector3f emitterPos = emitter.getPosition();
            float distanceSquared = position.distanceSquared(emitterPos);

            if (distanceSquared <= radiusSquared) {
                iterator.remove();
                removedCount++;
            }
        }

        return removedCount;
    }

    /**
     * Gets all sound emitters currently managed.
     * @return A copy of the emitters list
     */
    public List<SoundEmitter> getEmitters() {
        return new ArrayList<>(emitters);
    }

    /**
     * Gets all sound emitters that should be visible in debug mode.
     * @return A list of debug-visible emitters
     */
    public List<SoundEmitter> getDebugVisibleEmitters() {
        List<SoundEmitter> visibleEmitters = new ArrayList<>();
        for (SoundEmitter emitter : emitters) {
            if (emitter.isDebugVisible()) {
                visibleEmitters.add(emitter);
            }
        }
        return visibleEmitters;
    }

    /**
     * Gets the total number of managed emitters.
     * @return The number of emitters
     */
    public int getEmitterCount() {
        return emitters.size();
    }

    /**
     * Gets the number of active emitters.
     * @return The number of emitters that are currently active
     */
    public int getActiveEmitterCount() {
        int count = 0;
        for (SoundEmitter emitter : emitters) {
            if (emitter.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Clears all sound emitters.
     */
    public void clearAllEmitters() {
        emitters.clear();
    }

    /**
     * Sets the active state of all emitters.
     * @param active True to activate all emitters, false to deactivate
     */
    public void setAllEmittersActive(boolean active) {
        for (SoundEmitter emitter : emitters) {
            emitter.setActive(active);
        }
    }

    /**
     * Gets debug information about all emitters.
     * @return A list of debug info strings
     */
    public List<String> getDebugInfo() {
        List<String> debugInfo = new ArrayList<>();
        debugInfo.add("Sound Emitters: " + emitters.size() + " total, " + getActiveEmitterCount() + " active");

        for (int i = 0; i < emitters.size(); i++) {
            SoundEmitter emitter = emitters.get(i);
            debugInfo.add(String.format("[%d] %s", i, emitter.getDebugInfo()));
        }

        return debugInfo;
    }
}