# JavaScript mods

Mods for the browser build written in JavaScript: add one in the Mods panel and it runs at once,
with no rebuild. Press **F8** in the game's page (or click **Mods (F8)** in the corner), then **Add
.js files** or **Write one**. Each mod is kept in this browser and can be switched off or removed
there. ([ADR 0067](adr/0067-javascript-mods.md))

Examples: [`js-mods/coords-hud.js`](js-mods/coords-hud.js),
[`js-mods/fps-graph.js`](js-mods/fps-graph.js), [`js-mods/greeter.js`](js-mods/greeter.js).

**A JS mod runs with the page's full rights**, like any script on it: add only mods you trust.

Fabric mods (`.jar`) are different: the same panel sends them to the dev server on your PC, which
builds them into the game ([`fabric/README.md`](../fabric/README.md)). They aren't converted to
JavaScript; a Fabric mod changes the game's own code, which only a rebuild can do.

## A mod

```js
// @id hello
// @name Hello
// @version 1.0
// @description Says hello on the HUD.

api.on("hud", (draw) => {
  draw.text("Hello!", 4, 4, "#ffcc00");
});
```

The `// @` lines at the top are optional: without them, the file's name is the mod's name. The id
(letters, digits, `-`, `_`) is what a newer copy replaces.

## `api`

| Call | Does |
| --- | --- |
| `api.on(event, listener)`, `api.off(event, listener)` | Listens to an event (below) |
| `api.every(ticks, listener)` | Calls `listener` every `ticks` game ticks (20 a second) |
| `api.onKey(code, listener)` | A key pressed in the game, by [`KeyboardEvent.code`](https://developer.mozilla.org/docs/Web/API/KeyboardEvent/code) (`"KeyH"`, `"Digit1"`, `"F6"`); not while a screen or chat is open |
| `api.player()` | `{ x, y, z, heading, dimension, dead }`, or `null` outside a world |
| `api.inGame()` | In a world with no screen open |
| `api.screen()` | The open screen's class name (`"PauseScreen"`), or `""` |
| `api.world()` | The world or server's name, or `""` |
| `api.fps()`, `api.ticks()` | Frames a second; ticks since the game started |
| `api.key(name)` | Whether a game control is held: `"forward"`, `"back"`, `"left"`, `"right"`, `"jump"`, `"sneak"`, `"sprint"`, `"attack"`... |
| `api.say(text)` | Sends a chat message, or a command if it starts with `/` |
| `api.show(text)` | A chat line only you see |
| `api.notify(title, text)` | A message box |
| `api.copy(text)` | To the clipboard |
| `api.storage.get(key, fallback)`, `.set(key, value)`, `.remove(key)` | Saved per mod in this browser (JSON values) |
| `api.log(...values)` | To the browser console, tagged with the mod's id |
| `api.mod` | `{ id, name, version }` |

### Events

| Event | Listener gets |
| --- | --- |
| `start` | Once, when the game starts |
| `tick` | Every game tick |
| `join` | The server's address, or `""` for singleplayer |
| `leave` | |
| `click` | The mouse button (0 left, 1 right, 2 middle) |
| `attack` | How far away the attacked entity was |
| `kill` | |
| `screenshot` | The screenshot's file name |
| `hud` | `draw`, below: draw your HUD here, each frame the HUD is drawn |

### `draw`

In GUI units, `0, 0` the top left; colours as `0xAARRGGBB` numbers, `0xRRGGBB` (opaque), or
`"#rgb"`, `"#rrggbb"`, `"#aarrggbb"` strings.

| Call | Draws |
| --- | --- |
| `draw.width`, `draw.height` | The screen's size |
| `draw.text(text, x, y, color)` | Text in the game's font (white by default) |
| `draw.rect(x, y, width, height, color)` | A filled box (half-black by default) |
| `draw.circle(x, y, radius, color)`, `draw.ring(x, y, radius, lineWidth, color)` | Circles |
| `draw.push(x, y, scale)`, `draw.pop()` | Moves and scales what follows until `pop` |
| `draw.textWidth(text)`, `draw.lineHeight()` | Text's size |

## Keys, timers and `await`

The game answers while it's calling your mod: in an event listener and in `hud`. Anywhere else (an
`api.onKey` listener, a `setTimeout`, after an `await`), `api.player()`, `api.screen()` and the other
questions give what the game said at its last tick (a twentieth of a second ago; `null` or `""` if
nothing asked them in a tick yet), and `api.say`, `api.show`, `api.notify` and `api.copy` happen at
its next tick. `draw.textWidth` works only while drawing.

## Errors

A mod's errors are caught and shown under it in the panel; after 20 it's stopped until switched
off and on. An error a mod throws from its own `window` listeners or promises isn't caught and
ends the game, as any page error does: use `api.onKey`, and catch in `async` code.
