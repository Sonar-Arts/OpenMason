# Stonebreak test fixture

A frozen copy of part of Stonebreak's game resources (`stonebreak-game/src/main/resources`:
`sbo/`, `sbe/`, `ui/`, `fonts/Minecraft.ttf`) taken at Sonar-Arts/Stonebreak `544224cd`, when Open
Mason moved out of the Stonebreak repository (Stonebreak #388).

Tool tests use it as a real game's asset tree (`TestGameTargets.stonebreak()`, `GameUiFixtures`),
and `engine-voxel`'s `SBOPlanarGeometryTest` reads the shipped SBO blocks. It is test data only.
Nothing ships from here and it is never synced with the game automatically. Refresh it on purpose
when a test needs newer assets, and update the commit above.
