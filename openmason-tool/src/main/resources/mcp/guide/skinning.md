# Skinning (per-vertex weights)

Skin weights let one mesh bend over several bones. Each vertex has any number
of influences (joint + weight, normalized to 1; no cap); a joint is a bone OR a part. A
vertex with no weights follows its own part rigidly (the old per-part
behaviour), so weighting is optional and can be partial. Weights are stored per
part by part-local vertex index and saved in the .omo (`skin`).

Positions are **model space at the rest pose** (what the viewport shows with
no clip playing). Joints/parts are addressed by id or name; a bone name wins
over a part name.

## Tools
- `skin_info` — per part: verts, weighted count, joints used.
- `skin_auto_weights {parts?, bones?, falloff?, min_weight?, max_influences?, rigid?}`
  — bind by distance to each bone's rest head->tail segment (every bone, or the
  `max_influences` nearest; w ~ 1/d^2^falloff, then `min_weight` prunes).
  Replaces the parts' weights. `rigid:true` = nearest bone only.
- `skin_get_weights {part, vertices?}` / `skin_set_weights {part, entries:
  [{vertex, joints:[...], weights:[...]}]}` — exact per-vertex control.
- `skin_paint {center, radius, joint, mode, strength, part?}` — one brush dab:
  add / subtract / replace (toward strength) / smooth, smooth falloff.
- `skin_normalize`, `skin_prune {min_weight}`, `skin_clear {part?}`.
- `skin_mirror {axis, source_sign, tolerance?}` — copy one side onto the other
  across the model-space plane; joints swap by name (`_l`/`_r`, `.L`/`.R`,
  `left`/`right`, `L`/`R` camel suffix). Works across parts.
- Undo: `undo` / `redo` with domain:"skin" (own history; one step per tool call).

## Workflow
1. Build bones along the limbs (topic `bones`); rest pose = bind pose.
2. `skin_auto_weights` (try falloff 2-4; `rigid` for mechanical parts).
3. `skin_info` / `skin_get_weights` to check; fix joints with `skin_paint`
   (smooth seams) or `skin_set_weights`.
4. Model one side, then `skin_mirror {axis:"x", source_sign:1}`.
5. Verify: key the bones in a clip (`anim_new` + `anim_edit`) and open it in the Animation
   tab, which shows the skinned mesh posed.

In the editor: Rigging pane > Skin (bind, clean up, Weight Paint brush with a
heat map; Ctrl+Shift+P toggles paint mode, Ctrl+Z steps the skin history there).

## Shape keys (morph_*) and vertex groups (vgroup_*)
Other per-vertex data saved in the .omo, rows per part by part-local vertex.
Edits survive mesh edits (rows move to the nearest new vertex). Undo domain
"morph" covers both.
- Shape key = a sparse MODEL-space position delta per vertex from the basis
  mesh, weight 0..1 by default (range min/max, -10..10). `morph_list {key?,
  part?}`; `morph_set {name, create?, rename?, default?, min?, max?, remove?,
  value?}` (value = preview only).
- Make one: `morph_capture {mode:"begin"}`, move vertices with the normal
  tools, `morph_capture {mode:"mesh", name}` (the moves become the key, the mesh
  returns to the basis as a model undo step; keep_mesh:true leaves it), or
  `{mode:"deltas", name, part, vertices, deltas:[x,y,z,...], replace?}`
  (merges into the key's rows unless replace). Key weights in clips: `anim_edit` op
  `morph_key` (topic animation).
- Vertex group = named weighted vertices (weight > 0 = member; masks hide a
  face when all its vertices are in a hidden group). `vgroup_list {group?,
  part?}`; `vgroup_set {name, create?, part, vertices, weight? | weights?,
  unassign?, rename?, remove?}` (unassign without vertices = the whole part).

In the editor: Rigging pane > Shape Keys / Vertex Groups (also reorder, preview
sliders and "Hide" toggles; those previews are not saved).
