# Characters (.omchar)

A **character** turns a rigged model into something animation can address by ROLE: which joint plays `hips`,
`upper_arm_l`, `head`... of a **profile** (built in: `humanoid`, `quadruped`, `bird`; custom `.omprofile` files
beside the .omchar), a **reference pose** (T or A), joint limits, retarget **chains** and IK **goals**, **sockets**
(grip_r, grip_l, head, eyes, back + custom) and **metrics** (height, eye height, width, hip height). The format and
every rule are native (Cenda `cc_*`); `char_*` tools edit the character open in the **Character** tab (the user
sees it; tab and tools share one undo history, every tool call is one step, all or nothing).

Profile frame: +Y up, the character faces -Z, its own left is -X. **Sides come from geometry, not names**: a model
that names its sides from the viewer (`arm_left` on the character's right) maps correctly; never "fix" it by names.

## Recipe: characterize a model
1. `char_open {model:"project:Models/Hero.omo"}` (or `path` of an .omchar). Any model the engine reads.
2. `char_auto_map {profile:"humanoid"}` -> matches, facing, notes, `unmappedRequired`.
3. Fix roles: `char_edit {ops:[{op:"role", role:"hand_l", joint:"wrist_l"}]}` (null joint clears).
   `char_mirror {from:"left", what:["roles"]}` copies one side's roles by position.
4. `char_reference_pose {fit:"T"}` (or `"A"`; `char_get {section:"profile"}` lists the poses).
5. `char_sockets {place:["all"]}` (skips standard sockets whose role is unmapped), then nudge:
   `char_edit {ops:[{op:"socket", name:"eyes", pos:[0,1.62,-0.12]}]}` (rest model space).
6. `char_edit {ops:[{op:"compute_metrics"}]}` (measures the reference pose; typed values are overrides).
7. `char_validate` -> 0 errors, then `char_lock` (optional), `char_save {path:"Hero"}` -> project
   `Characters/Hero.omchar` (the model is referenced by path, relative when beside it).

## char_edit ops
- `{op:"role", role, joint|null}` - joints by id, name or `role:<role>`
- `{op:"pose", joint, rot:[x,y,z]|null}` - reference rotation, local Euler XYZ degrees (absolute)
- `{op:"limit", joint, min:[..], max:[..]}` / `{op:"limit", joint, remove:true}` - degrees from the reference pose
- `{op:"socket", name, joint?, pos?, rot?, scale?}` / `{..., remove:true}`; `{op:"standard_socket", name}`
- `{op:"chain", name, start, end, goal?}` / `{op:"goal", name, joint|null}`
- `{op:"metrics", height?, eye_height?, width?, hip_height?, overridden?:[...]}`, `{op:"compute_metrics"}`
- `{op:"field", name: name|objectId|profile|animGraph|modelRef|facing, value}` (facing `-z|+z|-x|+x`)

A locked character refuses role, profile, facing and reference-pose edits (`char_lock {unlock:true}` first).
`char_get {section}`: roles, joints, pose, limits, chains, goals, sockets, metrics, issues, profile.
