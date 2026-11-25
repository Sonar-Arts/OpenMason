# Skinning (per-vertex weights)

Skin weights let one mesh bend over several bones. Each vertex has up to 4
influences (joint + weight, normalized to 1); a joint is a bone OR a part. A
vertex with no weights follows its own part rigidly (the old per-part
behaviour), so weighting is optional and can be partial. Weights are stored per
part by part-local vertex index and saved in the .omo (`skin`).

Positions are **model space at the rest pose** (what the viewport shows with
no clip playing). Joints/parts are addressed by id or name; a bone name wins
over a part name.

## Tools
- `skin_info` — per part: verts, weighted count, joints used.
- `skin_auto_weights {parts?, bones?, falloff?, min_weight?, rigid?}` — bind by
  distance to each bone's rest head->tail segment (4 nearest, w ~ 1/d^2^falloff).
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
5. Verify: key the bones in a clip and `anim_apply_pose` + `viewport_capture`.

In the editor: Rigging pane > Skin (bind, clean up, Weight Paint brush with a
heat map; Ctrl+Shift+P toggles paint mode, Ctrl+Z steps the skin history there).
