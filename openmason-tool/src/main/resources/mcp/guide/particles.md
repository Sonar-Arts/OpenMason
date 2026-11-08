# Particles / FX systems (.omfx v2)

An **FX system** (campfire, explosion) is a set of **emitters** playing together: each emitter plays one
particle **effect** with its own offset and timeline. Systems expose **parameters** the game drives,
**bindings** wire them to values, **events** react to particle deaths/impacts/custom raises, plus an
optional **light** and **sounds**. Units: blocks, seconds, degrees; colours `#RRGGBBAA` (straight alpha).

`fx_*` documents are the agent's own (in memory until `fx_save`); the Particles tab edits the same v2 files
through the same JSON view (open a saved file there to show it to the user). Start: `fx_presets` -> `fx_new {id, preset}` -> `fx_get` -> `fx_set` -> `fx_simulate` /
`fx_capture` -> `fx_save`. Tools take `id`; omit it when one document is open.

## fx_get / fx_set shape (the file's own system.json)
```json
{"effects":[{"name":"fire","rate":55,"lifetime":[0.45,0.9],
   "shape":{"type":"disc","radius":0.22,"half":[0,0,0]},
   "speed":[0.5,1.1],"direction":[0,1,0],"spread":12,"gravity":[0,1,0],"drag":1.6,"wind":0.25,
   "startSize":[0.32,0.48],"endSize":[0.04,0.1],"sizeKeys":[[0,0.6],[0.15,1],[1,1]],
   "colorStart":"#FFF4D0FF","colorEnd":"#80180060","colorKeys":[[0,"#FFF4D0FF"],[1,"#80180060"]],
   "colorJitter":0.15,"fadeIn":0.1,"fadeOut":0.4,"additive":1,"emissive":true,"softness":0.25,
   "orientation":"camera","stretch":0,"rotation":[0,360],"spin":[-90,90],
   "sprite":"soft_disc","textures":[],"animate":false,
   "collision":{"enabled":false,"bounce":0.3,"friction":0.7},
   "subEmitters":[{"effect":"smoke","trigger":"death","count":1,"rate":0,"inherit":0.2}],
   "maxLive":0,"priority":"normal","lodDistance":64,"graph":{...optional FX graph IR...}}],
 "emitters":[{"name":"fire","effect":"fire","offset":[0,0,0],"delay":0,"duration":"forever",
   "loops":1,"interval":0,"burst":0,"rateScale":1,"sizeScale":1,"speedScale":1,
   "tint":"#FFFFFFFF","direction":null}],
 "parameters":[{"name":"intensity","type":"float","default":1},
   {"name":"push","type":"vector","default":[0,0,0]},{"name":"tint","type":"color","default":[1,1,1,1]}],
 "bindings":[{"parameter":"intensity","target":"rate","emitter":null,"scale":1,"bias":0}],
 "events":[{"trigger":"collision","source":"debris","spawnInto":"dust","count":2,"inherit":0.1,
   "chance":1,"forward":"debris-land","sound":"debris-hit"}],
 "light":{"color":"#FF9A48FF","intensity":1.4,"radius":9,"keys":[[0,1]],"loopKeys":false,
   "flicker":0.3,"flickerHz":7,"offset":[0,0.4,0]},
 "sounds":[{"event":"fire-ignite","resource":"/sounds/Fire.ogg","volume":1,"pitchMin":0.9,
   "pitchMax":1.1,"variation":true}],
 "cues":[{"trigger":"play","emitter":null,"sound":"fire-ignite","volume":1}]}
```
Enums: shape point|sphere|box|disc · orientation camera|velocity|axis_y|flat · sprite
soft_disc|square|texture · priority ambient|normal|critical · sub-emitter trigger
birth|death|collision|trail (trail uses `rate`) · binding target rate|burst|size|speed (float,
multiply `bias + scale*p`), tint|light_color (colour), direction|offset (vector), light_intensity|volume
(float, system-wide: emitter null) · event trigger death|collision (source = emitter) |custom
(source = event name, `raise`) · cue trigger play|emitter_start (names emitter)|stop.

## Timeline (emitters)
Cycle k starts at `delay + k*(duration+interval)`: fires `burst`, then emits at the effect's
`rate * rateScale` for `duration` ("forever" = until stopped). `loops` 0 = forever. One-shot burst:
`burst:40, duration:0`. Event-only (spawned into by events): `burst:0, rateScale:0, duration:0`.
A rate-0 effect only appears through bursts/events.

## Editing with fx_set (one undo step, validated like a file: a rejected edit changes nothing)
- `effects` / `emitters` / `parameters`: patches **upserted by name**; only the members you pass
  change (`{"effects":[{"name":"fire","rate":80}]}`); a new name adds one. `rename` renames and
  rewrites references (emitter.effect, subEmitters, bindings, events, cues).
- `remove: {"emitters":["smoke"]}` drops by name (references must go too, or the edit fails with why).
- `bindings` / `events` / `cues` / `sounds`: send the whole section. `light`: object, or null.
- Effect members replace whole: send all of `shape`/`collision` when changing one field of them.
`fx_undo {redo?}` steps back/forward.

## Sprites
Set an effect's `"sprite":"texture","textures":["smoke_0","smoke_1"]` (`animate:true` = flipbook
over life, false = one random frame per particle), then `fx_sprite {name:"smoke_0", path:...png|.omt}`
embeds the image under that name. Names not embedded are `externalTextures` (the game resolves them;
captures show a magenta checker).

## Verify
- `fx_simulate {seconds, params, raise:[{t,name}], stop_at}`: timeline of live counts per effect and
  bounds; `finishedAt` (one-shots), host `events`, `sounds`, `light`; warnings name emitters that never
  spawned and budget drops. Cheap: use it after every meaningful edit.
- `fx_capture {seconds, size, yaw, pitch, distance}`: the engine renderer's picture at that moment
  (floor grid = 1 block). Use a time where the effect is fully developed (peakAt from fx_simulate).

## Game data and export
A game plugin may register its own shipped FX container (`fx_presets` -> `exporters`; `deploys:true` = it
writes into the game target's checkout). Game data lives in the manifest `extensions` under the game's
namespace: `fx_set {extensions:{"<ns>:<name>": {...}}}` (null removes); `fx_get` shows it. `fx_export
{exporter?}` checks the system compiles, asks the container for problems, then writes: without `path` a
game container deploys into its asset folder (the write policy may ask the user), `path` exports elsewhere.
`openmason.omfx` is always registered (the neutral source).

## Files
`fx_save {path?}`: bare names land in the project's `Particles/`; documents opened from a v2 file
save back to it. `fx_open` reads v2, or migrates a v1 library (one document per effect, never saved
over the v1 file). Game data in manifest `extensions` and unknown archive files survive edits.
