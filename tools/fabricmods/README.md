# tools/fabricmods: Fabric mods at build time

Builds the Fabric mods in `mods/` into the client ([ADR 0065](../../docs/adr/0065-fabric-mods-at-build-time.md),
[`fabric/README.md`](../../fabric/README.md)). `fabric/runtime`'s `fabricMods` task runs it; it never ships.

| Class | Does |
| --- | --- |
| `Main` | Runs the steps below and writes `classes/`, `resources/fabric-mods/`, `src/` and `report.txt` |
| `ModScanner`, `ModJar` | Reads each jar's `fabric.mod.json` and its nested jars |
| `ClassIndex` | Every class by name: changed ones, the mods', the game's class path, the JDK's |
| `AccessWideners` | Applies the mods' access wideners to the game's classes |
| `MixinRun`, `OfflineMixinService`, `IndexClassLoader` | Fabric's Mixin and MixinExtras over the index, as Fabric Loader would while classes load |
| `BootHook` | Puts `FabricBoot.start(this)` in Minecraft's constructor after it sets the game directory |
| `ModTableWriter` | Writes the mod table: mods and entrypoints made with `new`, no reflection |
| `References`, `Report` | The classes a class names (generated classes, missing ones), and the report |

```sh
./gradlew :fabric:runtime:fabricMods   # output in fabric/runtime/build/fabric-mods/
```
