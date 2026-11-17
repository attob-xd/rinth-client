# fabric

- Experimental: a client without mods in `mods/` must build and run exactly as without this.
  `tools/fabricmods` changes no class and inserts no hook when there are no mods.
- `fabric/api` compiles Fabric Loader's public API from its sources jar (fetched, never committed);
  only Rinth's own classes behind it live in `src/`. Keep their signatures Fabric's: mods were
  compiled against them, and the browser build fails on any missing method.
- `fabric/api` has no game types: the build tool uses it on a normal JVM for mods' mixin plugins.
- The mods' code is third party: never copy it into Rinth's modules, never commit a mod jar
  (`*.jar` is git-ignored), and never publish a build with mods in it.
- Test: `./gradlew :tools:fabricmods:check :fabric:runtime:check`, then the test mod as
  `fabric/README.md` says.
