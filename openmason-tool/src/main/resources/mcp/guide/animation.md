# Animation (.omanim clips)

The Animation tab authors one clip for one model at a time, over a native
session the `anim_*` tools share (one undo history: `undo`/`redo` with
domain:"anim"). A clip has:
- **Authoring layers** (Maya-style): layer 0 = base; more layers on top, each
  `override` (replace, weighted; `passthrough` off = the rest pose where it has
  no keys) or `additive` (its difference from the rest pose is added; `meshSpace`
  for aim offsets), with weight, keyable weight (`layer_weight`), mute, solo and
  a weighted mask (`[{joint | role, weight, branch}]`).
- **A control rig**: effectors - `twoBone` IK (upper, mid, end; keys = target
  position, optional rotation pin and pole, weight 0..1 = FK..IK reach) and
  `aim` look-ats (joints, axis). Switch-and-match (`match` to ik / fk) changes
  control at a time without moving the limb.
- **Tracks**: notifies (point or range, JSON payload), float curves (LINEAR /
  STEP / CUBIC with tangents), sync markers, root motion (joint + x/y/z/yaw),
  additive reference (none / frame / character), and the runtime layer
  (`clip_layer`: BASE or OVERLAY + mask/fades/priority - how a game plays it).
Joint tracks are ALWAYS the bake of all that (every frame); the authoring data
is stored in the .omanim beside them (the runtime ignores it) and comes back
editable on open. A clip with nothing but base keys stays a plain clip.

Joints: a part OR a bone, by id or name, or `role:<role>` when the model is an
.omchar (topic `character`). Easing per key: LINEAR, EASE_IN, EASE_OUT,
EASE_IN_OUT, STEP.

## Opening
`anim_new {model, name?, fps?, duration?}` - model = .omo, a plugin's model
container, or an .omchar (roles, retargeting). `anim_open {path, model?}` opens
an .omanim on its modelRef (set on save) or on `model`. Both refuse to drop
unsaved changes without `discard:true`.

## Reading
- `anim_get_info`: header, clipLayer, layers (settings, tracks with key times,
  weightKeys), effectors (keys), notifies, curves, markers, rootMotion,
  additive; playhead, dirty.
- `anim_get_pose {time?, joints?, layer?, fk_only?}`: per joint the local
  pos/rot/scale and world point; the rig handles (target, weight, pole, where
  FK puts the chain end); root motion travel. `layer` adds that layer's OWN
  values (what keying it stores).

## Editing - `anim_edit {ops, label?}`: one undo step, all or nothing
- `header` {name?, fps?, duration?, loop?, modelRef?}; `clip_layer` {type?,
  mask?, fadeIn?, fadeOut?, priority?}
- `key` {layer?, joint, time, pos?, rot?, scale?, easing?} (omitted = the
  layer's current value there); `remove_keys` {layer?, joint?, times? | from?,
  to?}; `move_keys` {layer?, joint?, times, offset}; `easing` {..., times,
  easing}; `track` {layer?, joint, remove:true}
- `layer` {name, mode?, weight?, mute?, solo?, passthrough?, meshSpace?, mask?,
  index?, rename?, remove?}; `layer_weight` {layer, time, value | remove}
- `notify` {name, time, duration?, payload?} | {index, remove | members};
  `curve` {name, keys | null}; `marker` {name, time} | {index, remove};
  `root_motion` {joint, axes} | {} (off); `additive` {reference, time?, space?}
- `effector` {name, kind, upper/mid/end | joints, axis?, rename?, remove?};
  `effector_key` {effector, time, pos?, rot?, weight?, pole? | remove}
- `match` {effector, time, to: ik | fk, layer?}: to ik keys the target where FK
  has the limb (weight 1); to fk keys the limb's joints to the solved pose and
  the weight to 0, on `layer` or (none given) the topmost layer that holds the
  pose - an override at full weight with nothing above moving the limb; a
  layer that cannot hold it is refused.

## Transport, retarget, files
`anim_transport {action: play|pause|seek|fk_only|in_place, time?, value?}`.
`anim_retarget {source_character, source_clip, layer?}` bakes another
characterized model's clip onto this .omchar as a layer (one undo step).
`anim_save {path?}` (a bare name lands in the project's animation folder),
`anim_close`.

Workflow: key the base layer at t=0 and the extremes, layer corrections on
top, IK for planted feet/hands (match to IK, move the target, match back),
then events. Check with `anim_get_pose` (world points) at a few times.

## Scripted authoring (bulk keyframing)
For gait cycles, sine bobs or many keys, `run_python_script` with `om.anim`
(topic `scripting`) builds a detached clip (parts and bones) and saves it as
.omanim; `anim_open` it here. Or send many `key` ops in one `anim_edit`.
