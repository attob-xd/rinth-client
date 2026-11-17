# ADR 0065: Fabric mods built in at build time (experimental)

- Status: accepted, experimental
- Date: 2026-10-27
- Builds on: ADR 0001 (patch-only), ADR 0046 (module framework)

## Context

Players ask to run Fabric mods. Fabric Loader can't run in the browser: it loads classes at run
time and rewrites them as they load (Mixin), reflects on classes by name, and reads mods from jars
on disk. The browser build is one WebAssembly module compiled ahead of time by TeaVM, which has
none of that.

Minecraft 26.x isn't obfuscated, and Fabric's intermediary mappings for it are empty: mods for
26.3 use the game's own names, the names Rinth's decompiled tree has. So their mixins can target
Rinth's classes directly.

## Decision

Do Fabric Loader's work once, when the client is built, and compile the result in.

- **`tools/fabricmods`** reads the jars in `mods/` and their nested jars, applies the access
  wideners, and runs Fabric's own Mixin (0.17.5, Mixin 0.8.7) and MixinExtras (0.5.5) over the
  game's classes on a normal JVM, through an offline Mixin service in place of Knot's. Every
  config is made not required, so a mixin that doesn't fit is skipped with a warning; a class whose
  mixins fail keeps its own code. Every injector is made optional too: Rinth changes some of the
  game's code mods hook into, and an injector that finds no place costs the mod only that part,
  which the report names. The classes Mixin and MixinExtras generate are kept too, and accessor
  mixins' static accessors are filled in, as Mixin does when Fabric's class loader loads them.
- Classes the mods' code uses that nothing has (support for Quilt, or for a mod that isn't
  installed) get stand-ins that throw `NoClassDefFoundError` when reached: a JVM looks a class up
  only when code first uses it, but TeaVM links everything reachable ahead of time.
- It puts a call to `FabricBoot.start(this)` in Minecraft's constructor, after the game directory
  is set: where Fabric Loader's own hook goes, before options and key mappings are made.
- It writes a mod table, the mods and their entrypoints made with `new`, so nothing is looked up
  by name in the browser. The providers the mods' `META-INF/services` files list for their own
  services are bound the same way (`ModServices`, through web-classlib's `ServiceProviders`): the
  browser build's `ServiceLoader` reads no service files (ADR 0006). Their `Pattern.compile(regex,
  flags)` calls go to web-classlib's `UnicodePatterns`, which takes `UNICODE_CHARACTER_CLASS`.
  Their `sun.misc.Unsafe` is web-classlib's `OffHeapUnsafe`, whose address methods work on the
  browser's memory, and their method handles made from reflection are web-classlib's
  `ReflectiveHandles`, called through generated bridges; the game's libraries still find neither.
- It runs in its own game directory, as Fabric runs in a PC's, and that directory's `options.txt`
  names Vulkan as the graphics back end: the browser has no desktop OpenGL, and Vulkan is the only
  other back end mods know, so mixin plugins that leave out OpenGL-only mixins there (Iris) do here.
  What a mixin plugin decided at build time stays decided in the browser: its static fields of
  simple types are set again, to their build-time values, as its class initializes. This differs
  from Fabric, which decides once per launch from the player's own options.
- A mod's own pipelines (Sodium's terrain) are made as it runs, so the build can't list them. The
  WebGL2 device reports the ones it has no shaders for, the dev server keeps them in
  `mods/mod-shaders.txt`, and `tools/shaders` translates them from the mods' assets next time.
  Push constants, which only mods' shaders have, become a uniform block the device fills.
- Classes a mod makes as it runs can't load in the browser. Where the build knows the maker, it
  swaps in a class built in that does the same work: Sodium's vertex serializers copy by format
  through fabric/runtime's `VertexCopies`. A mod's own files outside `assets/` (Sodium's options
  logo) go on the pack's class path, for `Class.getResource`.
- **`fabric/api`** is Fabric Loader's public API compiled from the loader's sources, so every
  signature matches what mods were built against, with Rinth's own loader behind it.
- **`app-web`** puts the changed and added classes first on TeaVM's class path, and the mods'
  assets in the asset pack, which Rinth's pack serves.
- With `mods/` empty, nothing changes: no class, no hook.

## Consequences

- Some client-side mods for 26.3 work unchanged: mixins, access wideners, entrypoints, assets.
- A mod's code that the browser can't run (reflection by name, natives, threads, parts of the JDK
  TeaVM doesn't have) fails the whole browser build at link time; the fix is to remove the mod.
- A mod can run with parts missing where Fabric would have stopped the game: the report's `!`
  lines say which.
- The singleplayer server (a Web Worker, ADR 0039) runs without the mods: no data packs or server
  logic from them.
- Fabric API isn't supplied. Its modules can be tried as ordinary mods; most need more of the loader.
- Changing a mod means rebuilding the client (tens of minutes); there's no mod loading at run time.
- `mods/` is the player's own: mods are third party, never committed or shipped.
