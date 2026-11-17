# tools/fabricmods

- Build-time only; never referenced by code that ships.
- Apply mods as Fabric Loader would (its Mixin, its order, its hook point). Where this has to
  differ, the difference goes in ADR 0065 and the report says so.
- Never fail the build for one mod's problem: skip what doesn't fit and write a `!` line in the report.
- With no mods, change nothing: no classes, no hook, an empty table.
