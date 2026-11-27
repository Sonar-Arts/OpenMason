# Bones (rigging skeleton)

Bones and parts form ONE joint hierarchy, posed natively at runtime. A bone is
a mesh-less segment: head at `origin + position` in its parent's frame,
rotated by `rotation`, tail at `endpoint` (bone-local). Bones drive:
- **parts parented to them** (`bone_bind_part {part, bone}`) — the part hangs
  off the bone's TAIL, so rotating the bone swings the part;
- **skinned vertices** — per-vertex weights blend any number of bones/parts (topic
  `skinning`, `skin_*` tools); unweighted vertices follow their own part.
Parents may mix freely: bone under part, part under bone, bone under bone.

Animation clips may key bones as well as parts (a track keys a joint id; the
runtime binds by id, then name). Head tracking prefers a BONE whose name
contains "head" and that moves geometry (skin weights or child parts); else a
part named "head".

- `bone_list` — id, name, parent, rest transform, resolved world head/tail.
- `bone_create {name, parent_bone_id?, origin?, position?, rotation?, endpoint?}`
  — `parent_bone_id` may name a bone or a part.
- `bone_bind_part {part, bone}` — parent a part to a bone (rigid limb); one step on the
  MODEL history (`undo` domain:"model"), like reparenting in the Rigging pane.
- `bone_set_transform`, `bone_set_parent`, `bone_rename`, `bone_delete`
  (deleting a bone drops its skin influences; vertices renormalize).
- `bone_select` / `bone_clear` control the editor selection.
- Undo: `undo` / `redo` with domain:"bone" (own history).

Convention: one root bone at the model origin, a chain per limb
("spine", "head", "arm_l", "forearm_l", ...) with `_l`/`_r` side suffixes so
`skin_mirror` can swap sides. `model_summary` lists the skeleton compactly.
