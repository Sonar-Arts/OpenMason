/* Cenda sockets and mounts (Stonebreak #459, epic OpenMason#166): where things hang on a posed body, resolved
 * natively. Part of the rig ABI (cr_*, CR ABI still 2: functions were added); its own header because it also reads
 * a character definition (character.h).
 *
 * A SOCKET is a named frame on a rig joint (or on the model root), given in REST MODEL SPACE: the OMO attachment
 * convention the .omchar sockets follow. On a posed body its frame is
 *     joint socket:  pre · K(joint) · local          K = the joint's skin matrix (posed · rest^-1)
 *     root socket:   root · local
 * with local = T(pos) · Rx · Ry · Rz (Euler XYZ degrees, JOML rotateXYZ order) · S(scale). `pre` is what the
 * palette still lacks: the body's base matrix for a palette posed in model space, identity (NULL) for one posed
 * with its base. `root` is the base matrix (NULL = pre). Read the palette BEFORE cr_palette_hide (first person): a
 * hidden joint's skin matrix is scaled to its hide root.
 *
 * A SOCKET SET (cr_sockets) is every socket of one host model: the OMO's attachment points, the .omchar's sockets
 * and any the host adds. Names are case-insensitive and unique; a later add REPLACES an earlier one of the same
 * name when its source ranks at least as high (HOST > CHARACTER > OMO), so the .omchar socket wins over an OMO
 * attachment point of the same name. Look names up once (cr_sockets_find); frames take indices.
 *
 * TWO-SIDED ATTACHMENT (Roblox attachments, Unreal item sockets): a MOUNT hangs an item on a host socket. The item
 * carries its own frame for the mount: its ATTACH point (item space; the item frame lands so that the attach point
 * coincides with the socket) and/or an OFFSET applied on the socket:
 *     item frame = socket frame · offset · attach^-1
 * and named item POINTS (item space: a torch's flame, a rod's tip, a two-handed item's second grip):
 *     point frame = item frame · point
 * A MOUNT SET (cr_mounts) holds one body's mounts; cr_mounts_resolve writes every mount's and point's frame for
 * one posed palette in ONE call, allocating nothing.
 */
#ifndef CENDA_SOCKET_H
#define CENDA_SOCKET_H

#include <stdint.h>

#include "cenda/character.h"
#include "cenda/rig.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Where a socket came from; a later add of a name replaces an earlier one only when its source is >=. */
enum {
    CR_SOCKET_OMO = 0,       /* an OMO attachment point */
    CR_SOCKET_CHARACTER = 1, /* an .omchar socket */
    CR_SOCKET_HOST = 2       /* added by the host */
};

/* The joint of a socket on the model root. */
#define CR_SOCKET_ROOT (-1)

typedef struct cr_sockets cr_sockets;

/* A socket set over `rig` (which must outlive it); rig NULL = a set of root sockets only (an item's own points).
 * NULL on allocation failure. */
cr_sockets* cr_sockets_create(const cr_rig* rig);
void cr_sockets_destroy(cr_sockets* set);

/* Adds (or replaces, see above) socket `name` on `joint` (a rig joint index or CR_SOCKET_ROOT); pos / rot_deg NULL
 * = 0, scale NULL = 1. Returns the socket's index (an index stays valid; a replaced socket keeps its index), or
 * CR_ERR_ARG (empty name, joint out of the rig, non-finite values, bad source). A lower-ranked add of an existing
 * name changes nothing and returns the existing index. */
int32_t cr_sockets_add(cr_sockets* set, const char* name, int32_t joint, const float* pos, const float* rot_deg,
                       const float* scale, int32_t source);

/* An OMO attachment point: its host joint by id, else by exact name; both NULL / "" = a root socket. Returns the index, or CR_ERR_RANGE when the named joint is not in
 * the rig (the socket is left out: the OMO rule "a socket whose host is gone never resolves"). */
int32_t cr_sockets_add_ref(cr_sockets* set, const char* name, const char* joint_id, const char* joint_name,
                           const float* pos, const float* rot_deg, const float* scale, int32_t source);

int32_t cr_sockets_count(const cr_sockets* set);
/* Index of `name` (case-insensitive), or -1. */
int32_t cr_sockets_find(const cr_sockets* set, const char* name);
/* out_name: valid until the set changes. out_local: 16 floats (T·R·S). Any output may be NULL. */
int32_t cr_sockets_get(const cr_sockets* set, int32_t index, const char** out_name, int32_t* out_joint,
                       int32_t* out_source, float* out_local);

/* One socket's frame (16 floats) on a palette's skin matrices (joint_count * 16; joint_count must be the rig's).
 * pre / root as above (NULL = identity / pre). CR_ERR_RANGE when the socket's joint is outside the palette. */
int32_t cr_sockets_frame(const cr_sockets* set, int32_t index, const float* skin, int32_t joint_count,
                         const float* pre, const float* root, float* out);

/* Adds every .omchar socket of `c` to the set (source CHARACTER), resolving their joint ids on the set's rig
 * (joint id, then cr_rig_find). Returns how many were added; *out_unresolved (may be NULL) = sockets whose joint is not in the
 * rig (left out). CR_ERR_ARG on a NULL set/character or a set without a rig. */
int32_t cc_char_sockets_into(const cc_char* c, cr_sockets* set, int32_t* out_unresolved);

/* --- Mounts -------------------------------------------------------------------------------------- */

typedef struct cr_mounts cr_mounts;

cr_mounts* cr_mounts_create(void);
void cr_mounts_destroy(cr_mounts* mounts);

/* Sets mount `name` (case-insensitive; one item per name): the item hangs on host socket `socket` (an index into
 * the set it is resolved against; -1 = the host lacks it: the mount never resolves) with `offset` and the item's
 * `attach` point (16 floats each, NULL = identity). Replacing a mount keeps its index and drops its points.
 * Returns the mount's index or CR_ERR_ARG. */
int32_t cr_mounts_set(cr_mounts* mounts, const char* name, int32_t socket, const float* offset, const float* attach);
/* Removes a mount (later mounts move down one index). CR_ERR_RANGE when there is none of that name. */
int32_t cr_mounts_remove(cr_mounts* mounts, const char* name);
int32_t cr_mounts_clear(cr_mounts* mounts);
int32_t cr_mounts_count(const cr_mounts* mounts);
/* Index of mount `name` (case-insensitive), or -1. */
int32_t cr_mounts_find(const cr_mounts* mounts, const char* name);
/* out_name valid until the mounts change; out_socket = the host socket index. Either may be NULL. */
int32_t cr_mounts_get(const cr_mounts* mounts, int32_t index, const char** out_name, int32_t* out_socket);

/* Adds (or replaces, by case-insensitive name) a named point of mount `mount`'s item, in item space (16 floats,
 * NULL = identity). Returns its index within the mount. */
int32_t cr_mounts_set_point(cr_mounts* mounts, int32_t mount, const char* name, const float* local);
/* Points of one mount; the index of a point by name (-1 when absent). */
int32_t cr_mounts_point_count(const cr_mounts* mounts, int32_t mount);
int32_t cr_mounts_find_point(const cr_mounts* mounts, int32_t mount, const char* name);
/* Total points over every mount: the size (x16) of resolve's point output. A mount's points are consecutive, in
 * mount order; cr_mounts_point_base = the first one's index in that output. */
int32_t cr_mounts_total_points(const cr_mounts* mounts);
int32_t cr_mounts_point_base(const cr_mounts* mounts, int32_t mount);

/* Every mount's item frame (out_frames: count * 16) and every point's frame (out_points: total_points * 16, may be
 * NULL) on one posed palette of the set's host (skin: joint_count * 16; pre / root as for cr_sockets_frame).
 * out_valid (count bytes, may be NULL): 1 when the mount resolved; an unresolved mount (no socket, socket joint
 * outside the palette) gets zero matrices for itself and its points. Returns the number resolved, or CR_ERR_ARG. */
int32_t cr_mounts_resolve(const cr_mounts* mounts, const cr_sockets* sockets, const float* skin, int32_t joint_count,
                          const float* pre, const float* root, float* out_frames, float* out_points,
                          uint8_t* out_valid);

#ifdef __cplusplus
}
#endif

#endif
