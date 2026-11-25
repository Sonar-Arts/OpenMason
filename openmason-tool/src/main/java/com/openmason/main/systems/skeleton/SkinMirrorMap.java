package com.openmason.main.systems.skeleton;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The left/right partner of a joint by its NAME, for mirroring skin weights ({@code Skin.mirror} takes
 * the resulting index map; the mirroring itself is native). Recognised side tokens, tried in order:
 * <ol>
 *   <li>the words {@code left} / {@code right} in any case, as a word or a camel-case part
 *       ({@code arm_left}, {@code LeftLeg}, {@code armLeft}, {@code LEFT_EYE}) but not inside another
 *       word ({@code upright}, {@code leftover});</li>
 *   <li>a single {@code l}/{@code r} letter between separators ({@code _ . - space}) or at either end
 *       next to one ({@code arm_l}, {@code Arm.L}, {@code l_hand}, {@code hand_R_01});</li>
 *   <li>a capital {@code L}/{@code R} closing a camel-case name ({@code ArmL}) or opening one
 *       ({@code LArm}).</li>
 * </ol>
 * The swapped token keeps the original case. A name with no side token has no partner.
 */
public final class SkinMirrorMap {

    private static final Pattern WORD = Pattern.compile("(?i)left|right");
    private static final Pattern LETTER = Pattern.compile("(?<=^|[_.\\- ])([lLrR])(?=$|[_.\\- ])");
    private static final Pattern CAMEL_END = Pattern.compile("(?<=[a-z0-9])([LR])$");
    private static final Pattern CAMEL_START = Pattern.compile("^([LR])(?=[A-Z][a-z])");

    private SkinMirrorMap() {
    }

    /** The mirrored name, or null when {@code name} carries no side token. */
    public static String mirrorName(String name) {
        if (name == null || name.isEmpty()) return null;
        Matcher w = WORD.matcher(name);
        while (w.find()) {
            if (isWordBoundary(name, w.start(), w.end())) {
                return name.substring(0, w.start()) + swapWord(w.group()) + name.substring(w.end());
            }
        }
        for (Pattern p : new Pattern[] {LETTER, CAMEL_END, CAMEL_START}) {
            Matcher m = p.matcher(name);
            if (m.find()) {
                return name.substring(0, m.start(1)) + swapLetter(m.group(1).charAt(0)) + name.substring(m.end(1));
            }
        }
        return null;
    }

    /**
     * Partner index per joint: {@code map[i]} = the index in {@code names} of joint {@code i}'s mirrored
     * name (exact match first, then case-insensitive), or {@code i} itself when it has no partner.
     * Null names map to themselves.
     */
    public static int[] build(List<String> names) {
        Map<String, Integer> exact = new HashMap<>();
        Map<String, Integer> folded = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (n == null) continue;
            exact.putIfAbsent(n, i);
            folded.putIfAbsent(n.toLowerCase(java.util.Locale.ROOT), i);
        }
        int[] map = new int[names.size()];
        for (int i = 0; i < map.length; i++) {
            map[i] = i;
            String partner = mirrorName(names.get(i));
            if (partner == null) continue;
            Integer j = exact.get(partner);
            if (j == null) j = folded.get(partner.toLowerCase(java.util.Locale.ROOT));
            if (j != null) map[i] = j;
        }
        return map;
    }

    private static boolean isWordBoundary(String s, int start, int end) {
        boolean before = start == 0 || !Character.isLetter(s.charAt(start - 1))
                || (Character.isUpperCase(s.charAt(start)) && Character.isLowerCase(s.charAt(start - 1)));
        // A following lower-case letter means the word continues ("leftover"); a capital starts the
        // next camel-case part ("LeftArm").
        boolean after = end == s.length() || !Character.isLowerCase(s.charAt(end));
        return before && after;
    }

    private static boolean isAllUpper(String s, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isUpperCase(s.charAt(i))) return false;
        }
        return true;
    }

    private static String swapWord(String word) {
        String to = word.equalsIgnoreCase("left") ? "right" : "left";
        if (isAllUpper(word, 0, word.length())) return to.toUpperCase(java.util.Locale.ROOT);
        if (Character.isUpperCase(word.charAt(0))) return Character.toUpperCase(to.charAt(0)) + to.substring(1);
        return to;
    }

    private static char swapLetter(char c) {
        return switch (c) {
            case 'l' -> 'r';
            case 'r' -> 'l';
            case 'L' -> 'R';
            default -> 'L';
        };
    }
}
