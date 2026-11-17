# ADR 0067: JavaScript mods, and the Mods panel

- Status: accepted, experimental
- Date: 2026-10-28
- Builds on: ADR 0046 (module framework), ADR 0065 (Fabric mods at build time)

## Context

Players want to add a mod in the browser and have it work. Fabric mods can't: they change the
game's own code, which in the browser is one WebAssembly module compiled ahead of time
(ADR 0065), and a separately compiled mod can neither change it nor call into it. Changing the
game means rebuilding it.

## Decision

Two paths, one panel (F8 in the page):

- **JavaScript mods**, run at once. A mod is a `.js` file kept in the browser and run in the page
  with an `api` object (docs/js-mods.md): events, the player, chat, storage, and a HUD to draw on.
  The game's side is a script host behind the framework (`ScriptHost`, `Scripts`): the always-on
  JS Mods module (`features/jsmods`) hands it ticks, joins, clicks and the HUD; the browser's host
  (`rinth.app.web.jsmods.WebScriptHost`) passes them to the page's `jsmods.ts`. Events go by
  name, so new ones need no new host. The page answers the mods through one function,
  `window.rinthNative(op, arg)`, and the HUD comes back as one string of draw commands a frame.
  Actions that change the game (chat, messages) wait for the next tick.
- **Fabric mods**, converted on the player's PC. The panel sends a jar to the dev server
  (`ModConverter`, on `serve` only), which puts it in `mods/` and runs `tools/fabricmods` for the
  report in seconds, then rebuilds the browser build on request (about half an hour). The
  converter answers only requests that name the server as localhost and, to change anything, come
  from the page's own origin: building runs code from the mods.

Every call into a JS mod is caught: an error that reached the window would end the game.

## Consequences

- JS mods work at once but only through the API: they can't change how the game itself behaves.
- A JS mod has the page's full rights; the panel says to add only mods you trust.
- Fabric mods still need the PC and a rebuild; a hosted page has no converter.
- One string crossing a frame for the HUD, and one per question a mod asks.
