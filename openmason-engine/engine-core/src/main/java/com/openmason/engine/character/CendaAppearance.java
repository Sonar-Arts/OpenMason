package com.openmason.engine.character;

import com.openmason.engine.cenda.CendaKernels;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static com.openmason.engine.character.CendaCharacter.ADDR;
import static com.openmason.engine.character.CendaCharacter.F32;
import static com.openmason.engine.character.CendaCharacter.I32;
import static com.openmason.engine.character.CendaCharacter.I64;

/**
 * FFM handles of {@code cenda/appearance.h} (OpenMason #162, part of CC ABI 1) and the .omchar archive-entry
 * accessors. Loaded from the same library as {@link CendaCharacter} and available exactly when it is.
 */
final class CendaAppearance {

    /** {@code cc_garment_desc}: 2 pointers, ref count (+pad), positions, vertex count (+pad), skin, flags, reserved. */
    static final long GARMENT_DESC_BYTES = 64;
    /** {@code cc_item_desc}. */
    static final long ITEM_DESC_BYTES = 88;

    static final MethodHandle ENTRY_COUNT;
    static final MethodHandle ENTRY_GET;
    static final MethodHandle ENTRY_FIND;
    static final MethodHandle ENTRY_SET;
    static final MethodHandle LOOK_CREATE;
    static final MethodHandle LOOK_CLONE;
    static final MethodHandle LOOK_DESTROY;
    static final MethodHandle LOOK_READ;
    static final MethodHandle LOOK_WRITE_JSON;
    static final MethodHandle LOOK_WRITE_BINARY;
    static final MethodHandle LOOK_CHARACTER;
    static final MethodHandle LOOK_SET_CHARACTER;
    static final MethodHandle LOOK_COUNT;
    static final MethodHandle LOOK_FIND;
    static final MethodHandle LOOK_GET;
    static final MethodHandle LOOK_SET_COLOR;
    static final MethodHandle LOOK_SET_BOOL;
    static final MethodHandle LOOK_SET_STRING;
    static final MethodHandle LOOK_SET_NUMBER;
    static final MethodHandle LOOK_SET_JSON;
    static final MethodHandle LOOK_REMOVE;
    static final MethodHandle LOOK_EQUAL;
    static final MethodHandle LOOK_DIFF;
    static final MethodHandle LOOK_APPLY;
    static final MethodHandle GARMENT_CREATE;
    static final MethodHandle GARMENT_DESTROY;
    static final MethodHandle GARMENT_VERTEX_COUNT;
    static final MethodHandle GARMENT_POSITIONS;
    static final MethodHandle GARMENT_SKIN;
    static final MethodHandle GARMENT_PACK_SIZE;
    static final MethodHandle GARMENT_PACK;
    static final MethodHandle GARMENT_JOINT;
    static final MethodHandle APP_CREATE;
    static final MethodHandle APP_DESTROY;
    static final MethodHandle APP_SLOT_COUNT;
    static final MethodHandle APP_SLOT;
    static final MethodHandle APP_DEFAULTS;
    static final MethodHandle APP_ADD_ITEM;
    static final MethodHandle APP_ITEM_COUNT;
    static final MethodHandle APP_RESOLVE;
    static final MethodHandle APP_BODY;
    static final MethodHandle APP_VISIBLE;
    static final MethodHandle APP_MATERIAL_COUNT;
    static final MethodHandle APP_MATERIAL;
    static final MethodHandle APP_EQUIPPED_COUNT;
    static final MethodHandle APP_EQUIPPED;

    static {
        SymbolLookup lookup = null;
        if (CendaCharacter.isAvailable()) {
            Path lib = CendaKernels.libraryPath().orElse(null);
            if (lib != null) lookup = SymbolLookup.libraryLookup(lib, Arena.global());
        }
        ENTRY_COUNT = handle(lookup, "cc_char_entry_count", FunctionDescriptor.of(I32, ADDR));
        ENTRY_GET = handle(lookup, "cc_char_entry_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        ENTRY_FIND = handle(lookup, "cc_char_entry_find", FunctionDescriptor.of(I32, ADDR, ADDR));
        ENTRY_SET = handle(lookup, "cc_char_entry_set", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I64));
        LOOK_CREATE = handle(lookup, "cc_look_create", FunctionDescriptor.of(ADDR));
        LOOK_CLONE = handle(lookup, "cc_look_clone", FunctionDescriptor.of(ADDR, ADDR));
        LOOK_DESTROY = handle(lookup, "cc_look_destroy", FunctionDescriptor.ofVoid(ADDR));
        LOOK_READ = handle(lookup, "cc_look_read", FunctionDescriptor.of(ADDR, ADDR, I64, ADDR));
        LOOK_WRITE_JSON = handle(lookup, "cc_look_write_json", FunctionDescriptor.of(ADDR, ADDR));
        LOOK_WRITE_BINARY = handle(lookup, "cc_look_write_binary", FunctionDescriptor.of(ADDR, ADDR));
        LOOK_CHARACTER = handle(lookup, "cc_look_character", FunctionDescriptor.of(ADDR, ADDR));
        LOOK_SET_CHARACTER = handle(lookup, "cc_look_set_character", FunctionDescriptor.of(I32, ADDR, ADDR));
        LOOK_COUNT = handle(lookup, "cc_look_count", FunctionDescriptor.of(I32, ADDR));
        LOOK_FIND = handle(lookup, "cc_look_find", FunctionDescriptor.of(I32, ADDR, ADDR));
        LOOK_GET = handle(lookup, "cc_look_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        LOOK_SET_COLOR = handle(lookup, "cc_look_set_color", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        LOOK_SET_BOOL = handle(lookup, "cc_look_set_bool", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        LOOK_SET_STRING = handle(lookup, "cc_look_set_string", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        LOOK_SET_NUMBER = handle(lookup, "cc_look_set_number", FunctionDescriptor.of(I32, ADDR, ADDR, F32));
        LOOK_SET_JSON = handle(lookup, "cc_look_set_json", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        LOOK_REMOVE = handle(lookup, "cc_look_remove", FunctionDescriptor.of(I32, ADDR, ADDR));
        LOOK_EQUAL = handle(lookup, "cc_look_equal", FunctionDescriptor.of(I32, ADDR, ADDR));
        LOOK_DIFF = handle(lookup, "cc_look_diff", FunctionDescriptor.of(ADDR, ADDR, ADDR));
        LOOK_APPLY = handle(lookup, "cc_look_apply", FunctionDescriptor.of(I32, ADDR, ADDR));
        GARMENT_CREATE = handle(lookup, "cc_garment_create", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR, ADDR));
        GARMENT_DESTROY = handle(lookup, "cc_garment_destroy", FunctionDescriptor.ofVoid(ADDR));
        GARMENT_VERTEX_COUNT = handle(lookup, "cc_garment_vertex_count", FunctionDescriptor.of(I32, ADDR));
        GARMENT_POSITIONS = handle(lookup, "cc_garment_positions", FunctionDescriptor.of(I32, ADDR, ADDR));
        GARMENT_SKIN = handle(lookup, "cc_garment_skin", FunctionDescriptor.of(ADDR, ADDR));
        GARMENT_PACK_SIZE = handle(lookup, "cc_garment_pack_size", FunctionDescriptor.of(I32, ADDR));
        GARMENT_PACK = handle(lookup, "cc_garment_pack", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        GARMENT_JOINT = handle(lookup, "cc_garment_joint", FunctionDescriptor.of(I32, ADDR, I32));
        APP_CREATE = handle(lookup, "cc_appearance_create", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR, ADDR));
        APP_DESTROY = handle(lookup, "cc_appearance_destroy", FunctionDescriptor.ofVoid(ADDR));
        APP_SLOT_COUNT = handle(lookup, "cc_appearance_slot_count", FunctionDescriptor.of(I32, ADDR));
        APP_SLOT = handle(lookup, "cc_appearance_slot", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        APP_DEFAULTS = handle(lookup, "cc_appearance_defaults", FunctionDescriptor.of(I32, ADDR, ADDR));
        APP_ADD_ITEM = handle(lookup, "cc_appearance_add_item", FunctionDescriptor.of(I32, ADDR, ADDR));
        APP_ITEM_COUNT = handle(lookup, "cc_appearance_item_count", FunctionDescriptor.of(I32, ADDR));
        APP_RESOLVE = handle(lookup, "cc_appearance_resolve", FunctionDescriptor.of(I32, ADDR, ADDR));
        APP_BODY = handle(lookup, "cc_appearance_body", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        APP_VISIBLE = handle(lookup, "cc_appearance_visible", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32));
        APP_MATERIAL_COUNT = handle(lookup, "cc_appearance_material_count", FunctionDescriptor.of(I32, ADDR));
        APP_MATERIAL = handle(lookup, "cc_appearance_material", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        APP_EQUIPPED_COUNT = handle(lookup, "cc_appearance_equipped_count", FunctionDescriptor.of(I32, ADDR));
        APP_EQUIPPED = handle(lookup, "cc_appearance_equipped",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR, ADDR));
    }

    private CendaAppearance() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd))
            .orElseThrow(() -> new IllegalStateException("Cenda appearance symbol missing: " + name));
    }

    /** Copies and frees a native {@code cc_bytes}; null on a null result. */
    static byte[] take(MemorySegment bytes) throws Throwable {
        if (bytes.equals(MemorySegment.NULL)) return null;
        try {
            long size = (long) CendaCharacter.BYTES_SIZE.invokeExact(bytes);
            MemorySegment data = (MemorySegment) CendaCharacter.BYTES_DATA.invokeExact(bytes);
            return data.reinterpret(size).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        } finally {
            CendaCharacter.BYTES_DESTROY.invokeExact(bytes);
        }
    }

    static void check(int rc, String what) {
        if (rc >= 0) return;
        String why = CendaCharacter.lastError();
        if (rc == -1 || rc == -2) throw new IllegalArgumentException(what + ": " + why);  // CC_ERR_ARG / CC_ERR_RANGE
        throw new IllegalStateException(what + " failed (" + rc + "): " + why);
    }
}
