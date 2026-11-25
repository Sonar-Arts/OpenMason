package com.openmason.main.systems.skeleton;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The left/right naming convention behind skin mirroring. */
class SkinMirrorMapTest {

    @Test
    void separatorLetters() {
        assertEquals("arm_r", SkinMirrorMap.mirrorName("arm_l"));
        assertEquals("arm_l", SkinMirrorMap.mirrorName("arm_r"));
        assertEquals("Arm.R", SkinMirrorMap.mirrorName("Arm.L"));
        assertEquals("r_hand", SkinMirrorMap.mirrorName("l_hand"));
        assertEquals("hand_R_01", SkinMirrorMap.mirrorName("hand_L_01"));
        assertEquals("leg-l", SkinMirrorMap.mirrorName("leg-r"));
    }

    @Test
    void words() {
        assertEquals("RightLeg", SkinMirrorMap.mirrorName("LeftLeg"));
        assertEquals("leg_right", SkinMirrorMap.mirrorName("leg_left"));
        assertEquals("armLeft", SkinMirrorMap.mirrorName("armRight"));
        assertEquals("RIGHT_EYE", SkinMirrorMap.mirrorName("LEFT_EYE"));
        assertEquals("left", SkinMirrorMap.mirrorName("right"));
    }

    @Test
    void camelCaseLetters() {
        assertEquals("ArmR", SkinMirrorMap.mirrorName("ArmL"));
        assertEquals("RArm", SkinMirrorMap.mirrorName("LArm"));
    }

    @Test
    void namesWithoutASideHaveNoPartner() {
        assertNull(SkinMirrorMap.mirrorName("upright"));
        assertNull(SkinMirrorMap.mirrorName("leftover"));
        assertNull(SkinMirrorMap.mirrorName("body"));
        assertNull(SkinMirrorMap.mirrorName("Leg"));
        assertNull(SkinMirrorMap.mirrorName("tail"));
        assertNull(SkinMirrorMap.mirrorName(""));
        assertNull(SkinMirrorMap.mirrorName(null));
    }

    @Test
    void buildPairsByNameAndKeepsTheUnpaired() {
        int[] map = SkinMirrorMap.build(List.of("body", "arm_l", "arm_r", "Leg.L", "leg.r", "tail_l"));
        // body -> itself; arm_l <-> arm_r; Leg.L -> leg.r case-insensitively; tail_l has no tail_r.
        assertArrayEquals(new int[] {0, 2, 1, 4, 3, 5}, map);
    }
}
