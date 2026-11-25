package com.openmason.engine.anim;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A sync group ({@code cm_sync_advance}, OpenMason #156; Unreal sync groups and markers): the leader clip plays at
 * its own rate and every follower takes the leader's phase between same-named sync markers, so a walk/run blend
 * keeps the feet in step across clips of different lengths. Members without the leader's marker follow its
 * normalized time. Holds its member slots off-heap; {@link #advance} allocates nothing. Not thread-safe.
 */
public final class SyncGroup {

    private final MemorySegment members;
    private final AnimClipDocument[] clips;
    private int count;

    /** A group with room for {@code capacity} members. */
    public SyncGroup(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity < 1");
        this.members = Arena.ofAuto().allocate(CendaAnimClip.SYNC_MEMBER_BYTES * capacity, 8);
        this.clips = new AnimClipDocument[capacity];
    }

    public int size() {
        return count;
    }

    /** Removes every member. */
    public SyncGroup clear() {
        java.util.Arrays.fill(clips, 0, count, null);
        count = 0;
        return this;
    }

    /** Adds a member (its clip time, playback rate and blend weight); returns its index. */
    public int add(AnimClipDocument clip, float time, float rate, float weight) {
        if (count == clips.length) throw new IllegalStateException("sync group full");
        clips[count] = clip;
        long o = CendaAnimClip.SYNC_MEMBER_BYTES * count;
        members.set(CendaAnimClip.ADDR, o, clip.handle());
        members.set(CendaAnimClip.F32, o + 8, time);
        members.set(CendaAnimClip.F32, o + 12, rate);
        members.set(CendaAnimClip.F32, o + 16, weight);
        members.set(CendaAnimClip.I32, o + 20, 0);
        return count++;
    }

    /** Updates a member's rate and weight (its time is the group's). */
    public void set(int index, float rate, float weight) {
        check(index);
        long o = CendaAnimClip.SYNC_MEMBER_BYTES * index;
        members.set(CendaAnimClip.F32, o + 12, rate);
        members.set(CendaAnimClip.F32, o + 16, weight);
    }

    /** A member's current clip time. */
    public float time(int index) {
        check(index);
        return members.get(CendaAnimClip.F32, CendaAnimClip.SYNC_MEMBER_BYTES * index + 8);
    }

    /** Moves a member to a clip time (a restart). */
    public void setTime(int index, float time) {
        check(index);
        members.set(CendaAnimClip.F32, CendaAnimClip.SYNC_MEMBER_BYTES * index + 8, time);
    }

    /** Advances the group by {@code dt} seconds; {@code leader} -1 = the heaviest member. Returns the leader. */
    public int advance(int leader, float dt) {
        if (count == 0) return -1;
        try {
            int rc = (int) CendaAnimClip.SYNC_ADVANCE.invokeExact(members, count, leader, dt);
            if (rc < 0) throw new IllegalArgumentException("cm_sync_advance: " + CendaAnimClip.lastError());
            java.lang.ref.Reference.reachabilityFence(clips);
            return rc;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void check(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
    }
}
