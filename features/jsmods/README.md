# jsmods: JS Mods

The player's JavaScript mods: added, switched on and off and removed in the page's Mods panel
(press **F8** in the browser build), run with no rebuild. This module hands them the game's ticks,
joins and leaves and lets them draw over the HUD; the browser's script host
(`app-web`'s `rinth.app.web.jsmods`, and the page's `jsmods.ts`) runs them. Always on.
([ADR 0067](../../docs/adr/0067-javascript-mods.md); the API: [`docs/js-mods.md`](../../docs/js-mods.md))

```sh
./gradlew :features:jsmods:check
```
