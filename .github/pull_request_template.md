## Summary
<!-- What changed and why. -->


## Related issues
<!-- e.g. Resolve #12, or Resolve Sonar-Arts/Stonebreak#347 for issues still filed in the Stonebreak repo. -->


## Testing
<!-- Tests added, and run-tests.sh systems run. -->


## Checklist
- [ ] Title prefixed with area (`Open Mason:` / `Engine:` / `Cenda:`)
- [ ] Boundary tests pass (`EngineBoundaryTest`, `UiBoundaryTest`, `VoxelBoundaryTest`, `ToolHasNoGameReferencesTest`)
- [ ] New tests mapped in `Testing/systems.map`
- [ ] Cenda changes keep a pure-Java fallback (Lua/Flex have none: native ABI bumps noted)
- [ ] Cross-repo impact checked (Cenda SDK / FastNoise2 pin / `noise_nodes.hpp`, plugin-facing tool API)
- [ ] Screenshots for UI/visual changes
