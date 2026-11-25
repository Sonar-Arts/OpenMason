# Animation graphs (.omanimgraph)

An **animation graph** turns parameters (speed, grounded, jump...) into a pose every frame, for every character of a
profile: Unreal's Anim Graph / Unity's Animator / Godot's AnimationTree as data. It is written against clip SLOTS
(the character binds a clip to each) and profile ROLES, never a skeleton. The model, its checks, compilation and
evaluation are native (Cenda `cg_*`); `animgraph_*` tools edit the graph open in the **Animation Graph** tab (the user
sees it; tab and tools share one undo history; every `animgraph_apply` is one step, all or nothing).

Nodes form a DAG under the output (`root`): a node may feed several parents and is evaluated ONCE per frame (one
clip clock, influence summed). Node types: `clip` (slot, rate, rateParam, sync group), `blend1d` (float param,
samples by value), `blend2d` (vec2 param or paramX/paramY, cartesian | directional, samples x/y), `stateMachine`
(states -> nodes, transitions with conditions), `layer` (base + override/additive layers, mask, weight/weightParam),
`slot` (a montage slot: name, source, mask). Parameters: float, int, bool, trigger, vec2.

## Recipe: build a graph
1. `animgraph_new {name, profile:"humanoid"}` (or `animgraph_open {path}`; drafts open too).
2. `animgraph_apply {ops:[{op:"param",name:"speed",type:"float"}, {op:"slot",name:"walk"}, ...]}`
3. Nodes, then wiring: `{op:"node",id:"walk",type:"clip",slot:"walk"}`, `{op:"node",id:"move",type:"blend1d",param:"speed"}`,
   `{op:"link",parent:"move",child:"walk"}` (at: append | sample:<i> | state:<name> | layer:<i> | base | source),
   `{op:"sample",node:"move",index:1,value:1.5}`. The first node becomes the output; `{op:"graph",root:"id"}` changes it.
4. State machines: `{op:"node",id:"sm",type:"stateMachine"}`, `{op:"state",machine:"sm",name:"ground",node:"move",entry:true}`,
   `{op:"transition",machine:"sm",from:"ground",to:"jump",conditions:[{param:"jump"}],priority:2,blend:0.1}`
   (op == != > < >= <=; no op = truthy; a trigger is consumed when taken; exitTime = normalized progress; mode
   inertialize | crossfade; from "*" = any state). Edit one: `{op:"transition",machine,index,...}`; remove:true.
5. `animgraph_get {section:"problems"}` -> fix until empty of errors (a draft saves too, the runtime refuses it).
6. `{op:"layout"}` places nodes and states; `animgraph_save {path:"Locomotion"}` -> project `AnimGraphs/Locomotion.omanimgraph`.

Renames rewrite every reference: `{op:"param",name:"speed",rename:"velocity"}`, `{op:"node",id:"a",rename:"b"}`,
`{op:"state",machine,name,rename}`, `{op:"slot",name,rename}`. `{op:"node",id,remove:true}` unlinks it everywhere.
`{op:"node",id, <member>: value | null}` sets / clears any member (layers, mode, rate...). `{op:"section",name:
"modifiers"|"targets"|"colliders"|"masks", value:[...]}` replaces a whole section.

## Preview and debugging
`animgraph_preview {character:"Characters/Hero.omchar", slots:{walk:"model:Walking", run:"file:Clips/run.omanim"}}`
(saved in the graph's editor section; `model:` clips come from the character model's container) ->
`animgraph_evaluate {params:{speed:3}, triggers:["jump"], frames:30, restart:true}` returns per node: active,
influence, clip time, progress, input weights (blend samples / machine states / layer base + layers), machine state
and transition blend, plus notifies and curves - the numbers the tab's Debugger shows. `headless:true` evaluates a
fresh instance with the same inputs instead (the live preview is untouched).
