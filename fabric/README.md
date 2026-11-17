# fabric: Fabric mods built into the client (experimental)

Put Fabric mods made for Minecraft 26.3 in `mods/` at the repo root and build as usual: they're
compiled into the browser build. Expect some to work, not most. [ADR 0065](../docs/adr/0065-fabric-mods-at-build-time.md)
says why it's done this way and what it can't do.

```sh
./gradlew :app-web:teavm          # builds in whatever is in mods/ (empty: nothing changes)
./gradlew :app-web:serve          # then try it
```

After every build, read `fabric/runtime/build/fabric-mods/report.txt`: each mod, its mixins,
entrypoints and the problems found. `!` lines are problems.

## What works

| Fabric feature | Here |
| --- | --- |
| `fabric.mod.json`, nested jars (jar-in-jar) | Read; mods for servers only are skipped |
| Mixins, with MixinExtras | Applied when the client is built, with Fabric's own Mixin; a mixin that doesn't fit is skipped, not fatal, and an injector that finds no place loses only itself |
| Access wideners and class tweakers (`classTweaker v1`, `inject-interface`) | Applied when the client is built |
| Code for mods that aren't there (Quilt support, another mod's API) | Gets stand-in classes that throw `NoClassDefFoundError` only if reached, as a PC's JVM would |
| Reflection on the mods' own classes (`MyConfig.class.getDeclaredFields()`, config screens) | The classes looked at keep their members' metadata (`fabric-mods/reflection.txt`) |
| `ServiceLoader` (`META-INF/services`, multi-loader mods' platform helpers) | For mixin plugins while building, and in the browser for the mods' own services: the providers their service files list are bound when the client is built (`ModServices`) |
| `Pattern.compile` with `UNICODE_CHARACTER_CLASS` (Sodium's options search) | The mods' calls go to web-classlib's `UnicodePatterns`; TeaVM's regex refuses the flag |
| Mixin plugins that leave out OpenGL code on Vulkan (Iris) | They see the graphics back end as Vulkan (the browser has no desktop OpenGL), and their decisions carry into the browser build. Iris then runs as it does on Vulkan: no shader packs |
| `sun.misc.Unsafe` on native memory (Sodium's `MemoryIntrinsics`) | The mods' `Unsafe` is web-classlib's `OffHeapUnsafe`: the methods that take an address work on the browser's memory |
| Method handles made from reflection (`unreflectGetter`, then `invokeExact`) | Made over the reflected member (web-classlib's `ReflectiveHandles`), called through generated bridges |
| A mod's own shaders (Sodium's terrain) | Translated like the game's, once the game has asked for them: see below |
| Classes a mod makes as it runs (Sodium's vertex serializers) | Only known ones: Sodium's copy by format through `VertexCopies` |
| A mod's files read with `Class.getResource` (Sodium's options logo) | On the pack's class path |
| A mixin plugin that fails to load | Only its config's mixins are left out (Fabric would stop the game); the report says why |
| Turning a mod off and on | The ON/OFF switch beside each mod in Mod Menu (`fabric/modtoggles`): no rebuild, just a reload. The client is built with every combination of the switchable mods (`ModSwitchPlan`; all of them while there are few, else each one off on its own) and merged into one (`VariantMerge`): a method whose code differs between them switches on the combination the game started with (`ModSwitchState`, saved in the game directory's `config/rinth-mod-switches.txt`). A mod that's off isn't loaded or started, and its handlers return at once. Mod Menu and the switches, what they need and mods nested in another jar have no switch. The Mods panel's Turn off (F8) still makes the jar `<jar>.disabled`, which the next build leaves out altogether |
| `main` and `client` entrypoints | Run where Fabric runs them, in Minecraft's constructor; one that throws is logged and skipped |
| Other entrypoints (`modmenu`...) | Listed for `FabricLoader.getEntrypoints` when their classes are all here |
| `FabricLoader` API | Mods, metadata, entrypoints, object share, game and config directories; a mod's assets through `findPath`/`getPath` (copied to `.fabric/mods/<id>/` on first use) |
| Assets (textures, models, language files) | Served from Rinth's pack |

## What doesn't

- **Mods for other versions.** The game's code must match the mod's: 26.3 only.
- **Server-side changes in singleplayer.** The singleplayer server runs in a Web Worker without the
  mods; their data packs and server code don't run there.
- **Anything the browser can't do.** Reflection on classes by name, native code, Java's HTTP client
  (`java.net.http`, which update checkers use), files outside the game directory, Java agents. A
  mod's call into such a thing throws when it's reached (`tools/survey/rules.txt`, "Fabric mods'
  code for what the browser lacks"; `app-web/build/teavm/removed.txt` lists them): fine where the
  mod catches it, as update checkers do, but a mod that doesn't can stop the game, as on a PC
  missing that part of Java. Virtual threads work: they're ordinary threads here.
- **Fabric API.** Not supplied. Its modules can be put in `mods/` like any mod, but they're large and
  lean on loader features this doesn't have; expect parts to fail.
- **Entrypoints with another language adapter** (Kotlin) or `Class::member` declarations.
- **Shader packs** (Iris). They need desktop OpenGL 4; Iris runs here as on Vulkan, without them.

## A mod's shaders

A mod makes its pipelines as it runs (Sodium's terrain, with defines for each pass), so the build
can't list them as it lists the game's. On the dev server the first time, the page has no shaders
for them: the WebGL2 device skips those draws, and the page has the dev server keep each
pipeline in `mods/mod-shaders.txt`. Then translate again and reload; the Java build needn't run:

```sh
./gradlew :app-web:shaders :app-web:buildManifest
```

A mod that fails a mixin keeps working where it can: the class its mixin targeted keeps its own code,
and the report names the mod. Injectors are all made optional, because Rinth changes some of the
game's code that mods hook into (its pause menu has no Player Reporting button, for one): where an
injector finds no place, the report says `found no place in ...` and only that part of the mod is
missing. Fabric would stop the game instead.

## Modules

| Module | What it is |
| --- | --- |
| `fabric/api` | Fabric Loader's public API, compiled from the loader's sources, with Rinth's own loader behind it |
| `fabric/runtime` | Runs `tools/fabricmods` over `mods/` (`fabricMods`) and starts the mods (`FabricBoot`) |
| `fabric/testmod` | A small Fabric mod that tests the path: `./gradlew :fabric:testmod:installTestMod`, then build |
| `fabric/modtoggles` | Rinth's ON/OFF switches in Mod Menu's list, built against the Mod Menu jar in `mods/`: `./gradlew :fabric:modtoggles:installModToggles`, then build |
| `tools/fabricmods` | The build-time processor: reads the jars, applies access wideners and mixins, writes the classes, assets and mod table |

## What a switch can't reach

A constructor switches after its superclass's constructor, so a mod that changes what a
constructor passes to its superclass's isn't switched there: it runs as with every mod on, and the
report lists it under "Switches" (two particle constructors, with Sodium). A switched-off mod's
handlers that return nothing return at once even there; those that return a value (a `@Redirect`,
say) still run. Each combination adds a run of the processor, in parallel with the main one:
seconds, not the 35-minute TeaVM step.

## How to test

```sh
./gradlew :tools:fabricmods:check :fabric:runtime:check
./gradlew :fabric:testmod:installTestMod     # copies rinth-testmod.jar into mods/
./gradlew :fabric:runtime:fabricMods         # report: 1 mod, mixins applied to 2 of 2 targets
./gradlew :app-web:teavm :app-web:serve      # the title screen: a yellow line, and the splash "Fabric mods work!"
```

Remove `mods/rinth-testmod.jar` afterwards to build without it.
