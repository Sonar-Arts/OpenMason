/* Cenda character appearance (OpenMason #162, part of CC ABI 1; Roblox HumanoidDescription as data): what a
 * character looks like, as one small record that is saved with a player and sent to every client, resolved
 * natively against the character into what to draw. Consumed by Java via FFM (com.openmason.engine.character).
 *
 * SLOTS are declared in the .omchar's "appearance" object (cc_char_set_string CC_FIELD_APPEARANCE):
 *   tints      [{name, default: "#rrggbb"}]
 *              colour channels (skin, hair, eyes, cloth)
 *   materials  [{id, tint: "<tint>"} | {id, mask: "<zip entry>", channels: {r?, g?, b?, a?: "<tint>"}}]
 *              which body materials a tint dyes: a whole material, or (a dye mask, an RGBA image stored in
 *              the .omchar) up to four tints within one material, each where its mask channel says
 *   groups     [{name, parts: [<joint id>, ...], visible?: true}]
 *              part visibility groups (a group slot's value shows or hides its parts)
 *   bodies     [{name, model: "<zip entry>"}]
 *              body variants sharing the skeleton (other meshes for the same joint set); the "body" slot picks
 *              one, the first is the default, none declared = the skeleton model
 *   equipment  [{name, kind?: rigid | skinned, socket?: "<socket>", hides?: [<group>, ...]}]
 *              equipment slots; a slot's value is an item id the host registered (cc_appearance_add_item)
 * Slot names are unique across tints, groups and equipment; "body" is reserved for the body variant.
 *
 * The RECORD (cc_look): slot name -> value. Values: colour (8 bits per channel: "#rrggbb"), bool, string (a
 * body name, an item id, "" = empty), number, JSON (anything else, kept verbatim). Unknown slots and unknown
 * value types survive every round trip. Two encodings, both deterministic (write(read(x)) == x):
 *   JSON    {"format": "omlook", "version": "1.0", "character"?: "<objectId>", "slots": {"<slot>": value}}
 *           colour = a "#rrggbb" string, null = a removal (patches), an unknown binary type = {"$type": t,
 *           "$hex": "<payload>"}
 *   binary  "OL" major minor, varint character length + bytes, varint count, per entry: varint name length +
 *           name, u8 type, varint payload length + payload (colour 3 bytes, bool 1, string/JSON UTF-8, number
 *           f32 little-endian, removal 0). A newer major is refused (CC_ERR_VERSION).
 * cc_look_diff / cc_look_apply make and apply patches, so a game replicates only what changed.
 *
 * RESOLVE (cc_appearance): character + rig + record + registered items -> the body variant, the visible parts
 * (authored OMO visibility via CR_JOINT_HIDDEN, group slots, groups the equipment hides, an optional view mask
 * such as cc_char_view_mask's), the tint of every dyed material, and the equipped items (rigid: socket joint +
 * transform; skinned: a cc_garment drawn with the body's own palette).
 *
 * GARMENTS (cc_garment): skinned equipment - boots, gloves, tunics that bend with the body - the way Unreal's
 * leader pose / Godot's shared skeleton do it: the garment's skin joints are remapped onto the body's joints
 * once, and it draws with the body's joint palette (no per-frame cost). A garment authored on ANOTHER character
 * of the same profile is first FITTED: its rest vertices are posed into the source's reference pose, moved
 * joint by joint onto the target's reference pose (profile frame; turned onto the target bone, scaled along it
 * by the length ratio and across by the characters' size ratio), and unposed with the target's skin, so it
 * sits on the target's rest pose.
 */
#pragma once

#include <stdint.h>

#include "cenda/character.h"
#include "cenda/rig.h"

#ifdef __cplusplus
extern "C" {
#endif

/* --- Record --------------------------------------------------------------------------------------------- */

typedef struct cc_look cc_look;

/* Value types (binary type tags; 6..255 = unknown, kept as raw payload). */
enum {
    CC_LOOK_REMOVE = 0,  /* a patch entry: the slot is removed */
    CC_LOOK_COLOR = 1,
    CC_LOOK_BOOL = 2,
    CC_LOOK_STRING = 3,
    CC_LOOK_NUMBER = 4,
    CC_LOOK_JSON = 5
};

cc_look* cc_look_create(void);
cc_look* cc_look_clone(const cc_look* look);
void cc_look_destroy(cc_look* look);
/* JSON (first non-blank byte '{') or binary ("OL"). NULL on failure; *out_result = CC_ERR_FORMAT /
 * CC_ERR_VERSION / CC_ERR_ARG and cc_last_error says why. */
cc_look* cc_look_read(const uint8_t* bytes, int64_t length, int32_t* out_result);
cc_bytes* cc_look_write_json(const cc_look* look);
cc_bytes* cc_look_write_binary(const cc_look* look);

/* The character the record is for (its objectId; "" = any). */
const char* cc_look_character(const cc_look* look);
int32_t cc_look_set_character(cc_look* look, const char* object_id);

int32_t cc_look_count(const cc_look* look);
/* -1 when absent. */
int32_t cc_look_find(const cc_look* look, const char* name);
/* An entry: any output may be NULL. out_color: 3 floats 0..1 (colour); out_flag: bool; out_number: number;
 * out_text: string / JSON text / an unknown type's payload as hex (owned by the look, valid until it changes). */
int32_t cc_look_get(const cc_look* look, int32_t index, const char** out_name, int32_t* out_type, float* out_color,
                    int32_t* out_flag, float* out_number, const char** out_text);
/* Setters replace the slot's value in place or append it. A colour is stored with 8 bits per channel. */
int32_t cc_look_set_color(cc_look* look, const char* name, const float* rgb);
int32_t cc_look_set_bool(cc_look* look, const char* name, int32_t value);
int32_t cc_look_set_string(cc_look* look, const char* name, const char* value);
int32_t cc_look_set_number(cc_look* look, const char* name, float value);
/* json: any JSON value except a string / bool / number / null (those have their own setters). */
int32_t cc_look_set_json(cc_look* look, const char* name, const char* json);
int32_t cc_look_remove(cc_look* look, const char* name);
/* 1 when both hold the same character and the same slots with the same values (order ignored). */
int32_t cc_look_equal(const cc_look* a, const cc_look* b);
/* A patch turning `from` into `to`: changed and added slots, CC_LOOK_REMOVE entries for removed ones, the
 * character when it changed. */
cc_look* cc_look_diff(const cc_look* from, const cc_look* to);
/* Applies a patch (or any record: its slots overwrite). */
int32_t cc_look_apply(cc_look* look, const cc_look* patch);

/* --- Garments (skinned equipment) ------------------------------------------------------------------------ */

typedef struct cc_garment cc_garment;

typedef struct cc_garment_desc {
    const cc_char* source;          /* the character it was authored on; NULL = the target itself */
    const cr_rig* source_rig;       /* that character's rig (NULL with source NULL) */
    const char* const* joint_refs;  /* the garment skin's joints: an id or name in the source rig, or "role:<r>" */
    int32_t ref_count;
    const float* positions;         /* vertex_count*3, rest pose, the source's model space */
    int32_t vertex_count;
    const cr_skin* skin;            /* weights over joint_refs indices; an unweighted vertex follows ref 0 */
    uint32_t flags;                 /* 0 */
    uint32_t reserved;
} cc_garment_desc;

/* Fits a garment to the target character: *out_unresolved (may be NULL) counts joint refs that map to no target
 * joint (their weight moves to the nearest mapped ancestor; none = ref 0's target). NULL on failure. */
cc_garment* cc_garment_create(const cc_char* target, const cr_rig* target_rig, const cc_garment_desc* desc,
                              int32_t* out_unresolved);
void cc_garment_destroy(cc_garment* g);
int32_t cc_garment_vertex_count(const cc_garment* g);
/* The fitted rest positions, the target's model space (vertex_count*3). */
int32_t cc_garment_positions(const cc_garment* g, float* out);
/* The weights over TARGET joint indices (owned by the garment): pose / deform with the body's palette. */
const cr_skin* cc_garment_skin(const cc_garment* g);
/* GPU attributes for the target's palette: 4 x u16 joints + 4 x unorm8 weights per vertex (cr_skin_pack). */
int32_t cc_garment_pack(const cc_garment* g, uint16_t* out_joints, uint8_t* out_weights);
/* The target joint a ref resolved to (-1 = unresolved). */
int32_t cc_garment_joint(const cc_garment* g, int32_t ref);

/* --- Resolution ------------------------------------------------------------------------------------------ */

typedef struct cc_appearance cc_appearance;

/* Slot kinds. */
enum {
    CC_SLOT_TINT = 0,
    CC_SLOT_GROUP = 1,
    CC_SLOT_BODY = 2,
    CC_SLOT_EQUIPMENT = 3
};

/* Item kinds. */
enum {
    CC_ITEM_RIGID = 0,    /* a model on a socket (today's attachments) */
    CC_ITEM_SKINNED = 1   /* a cc_garment drawn with the body's palette */
};

typedef struct cc_item_desc {
    const char* id;                 /* the value an equipment slot holds */
    const char* slot;               /* the equipment slot it fits */
    int32_t kind;                   /* CC_ITEM_* */
    uint32_t flags;                 /* 0 */
    const char* socket;             /* rigid: the socket; NULL = the slot's */
    float pos[3], rot[3], scale[3]; /* rigid: the item's offset on the socket (Euler XYZ degrees) */
    float reserved;
    const char* const* hides;       /* groups this item hides, beyond the slot's */
    int32_t hide_count;
    int32_t reserved2;
} cc_item_desc;

/* Parses and checks the character's appearance section against `rig` (the character and rig must outlive it).
 * NULL on failure (*out_result = CC_ERR_FORMAT for a bad section). *out_unresolved counts references the rig
 * or character lacks (group parts, sockets, mask channels naming no tint): they are left out. */
cc_appearance* cc_appearance_create(const cc_char* c, const cr_rig* rig, int32_t* out_result, int32_t* out_unresolved);
void cc_appearance_destroy(cc_appearance* a);

int32_t cc_appearance_slot_count(const cc_appearance* a);
/* out_kind CC_SLOT_*; out_type the value type it takes (CC_LOOK_*). */
int32_t cc_appearance_slot(const cc_appearance* a, int32_t index, const char** out_name, int32_t* out_kind,
                           int32_t* out_type);
/* Fills `out` with every declared slot at its default (tints' colours, groups' visibility, the first body,
 * empty equipment). */
int32_t cc_appearance_defaults(const cc_appearance* a, cc_look* out);

/* Registers an equipment item; returns its index or CC_ERR_*. An id registered twice replaces the first. */
int32_t cc_appearance_add_item(cc_appearance* a, const cc_item_desc* item);
int32_t cc_appearance_item_count(const cc_appearance* a);

/* Resolves a record: slots it lacks take their defaults; unknown slots are ignored (they stay in the record); a
 * value of the wrong type, an unknown body or an item that is not registered for the slot falls back to the
 * default. Returns how many slots fell back (>= 0) or CC_ERR_*. */
int32_t cc_appearance_resolve(cc_appearance* a, const cc_look* look);

/* The resolved body: its name and model entry ("" = the skeleton model). */
int32_t cc_appearance_body(const cc_appearance* a, const char** out_name, const char** out_model);
/* Visible joints as a bitset (bit j of word j/64), words = ceil(joints/64): a joint is visible when it is not
 * authored hidden, no group hides it and (view non-NULL) its view weight is above 0. */
int32_t cc_appearance_visible(const cc_appearance* a, const cr_mask* view, uint64_t* out_bits, int32_t words);
/* Dyed materials of the resolved record. out_tints: 4 x rgb (a channel without a tint = 1,1,1); out_mask: the
 * dye-mask entry ("" = none: the material is dyed whole by tint 0); */
int32_t cc_appearance_material_count(const cc_appearance* a);
int32_t cc_appearance_material(const cc_appearance* a, int32_t index, int32_t* out_material_id, const char** out_mask,
                               float* out_tints);
/* Equipped items of the resolved record, in slot order. out_item: the registered item index; rigid items:
 * out_joint = the socket's joint and out_matrix (16) = socket x item offset in that joint's REST model space
 * (world = the joint's skin matrix x out_matrix); skinned items: out_joint -1, out_matrix identity. */
int32_t cc_appearance_equipped_count(const cc_appearance* a);
int32_t cc_appearance_equipped(const cc_appearance* a, int32_t index, const char** out_slot, int32_t* out_item,
                               int32_t* out_kind, int32_t* out_joint, float* out_matrix);

#ifdef __cplusplus
}
#endif
