# Goblin combat animation handoff

`SB_Goblin.sbe` carries six BASE clips authored against the current `Goblin.omo`
(21 parts, faces -Z, ~2.25 units tall; the game renders it at `Goblin.MODEL_SCALE` 0.6).
`clips.json` is the timing contract; `MobWeapon` in code must match it, and
`GoblinAssetContractTest` fails if the two drift.

| State | Seconds | Use |
|---|---:|---|
| Idle | 2.0, loop | Standing / holding at range. |
| Walking | 0.8, loop | Wander and chase. |
| Stab | 0.6 | Dagger jab; damage lands at **0.25 s**. |
| Smash | 0.9 | Patty smacker overhead slam; damage lands at **0.55 s**. |
| DrawBow | 1.2 | Bow draw, holds full draw; arrow leaves at the end. |
| ReleaseBow | 0.4 | Follow-through from full draw back to rest. |

Every one-shot starts and ends at rest (DrawBow ends at full draw, which ReleaseBow
starts from), because the mob renderer does not blend between states.

## Rig notes

- Part pivots (`origin`) are offsets from the part's own position — that is how both
  Open Mason and `SbePoseSolver` read them. They were once authored as absolute points,
  which tore limbs off the moment anything rotated.
- Legs chain `thigh -> shin -> foot`, so bending a thigh carries the lower leg.
- **Skeleton (re-rigged 2026-11-25, OpenMason #160)**: 16 bones on the part pivots with tails
  along the limbs (`root -> hips -> spine`, `neck`, `shoulder/elbow/wrist_l|r`, `hip/knee/ankle_l|r`), each inserted
  between a part and its old parent part (`spine -> body`, `hip_l -> thigh_l -> knee_l -> shin_l -> ankle_l -> foot_l`,
  ...), so every part keeps its ancestors and `body` still carries everything. A re-parented part's rest `pos` and
  its clips' `pos` keys moved by the new parent frame's offset, so the six clips pose exactly as before (OpenMason
  `GoblinRerigParityTest`). The humanoid auto-map now maps all 15 required roles (hands = wrists, feet = ankles),
  so retargeted clips and IK drive the goblin through its bones. The old 12 bones were unbound placeholders (1 unit
  long, pointing up, knees on the hips).
- Weapons are voxelized item sprites mounted on `weapon_r` / `bow_l`. In a socket's frame
  an item is centred, one unit across, long axis along +Y (`DropRenderer.SOCKET_SPRITE_TRANSFORM`);
  the socket's rotation/scale does the fitting. Clips counter-rotate `hand_r` / `hand_l`
  so the blade keeps pointing forward and the bow stays upright through the swing.
- `Hatzone` and `Earring` use the same names as the player's sockets, so one cosmetic
  asset (`sbe/Clothing/*`) fits both; each host's socket scale carries the fit.

## Sources

`SB_Goblin.sbe` itself is the source of the model, its skeleton and its clips (the earlier
`Dev Working/Goblin/` sources and the clip-authoring script predate the #160 re-rig and were
retired). Edit it in Open Mason; keep `clips.json` and `MobWeapon` in step with any timing change.
