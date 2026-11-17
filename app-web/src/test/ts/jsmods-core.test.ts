import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { GameLink, HudCommands, parseHeader, toColor, toId } from "../../main/ts/jsmods-core.ts";

/** A link to a pretend game that answers `op:argument#calls` and records what it was asked. */
function fakeGame(): { link: GameLink; asked: string[] } {
  const asked: string[] = [];
  const link = new GameLink(
    () => (op, argument) => {
      asked.push(`${op}:${argument}`);
      return `${op}:${argument}#${String(asked.length)}`;
    },
    () => {
      assert.fail("nothing should fail");
    },
  );
  return { link, asked };
}

describe("asking the game", () => {
  it("asks it during its own calls", () => {
    const { link, asked } = fakeGame();
    assert.equal(
      link.during(() => link.ask("player")),
      "player:#1",
    );
    assert.deepEqual(asked, ["player:"]);
  });

  it("answers from its last tick outside them, and leaves it be", () => {
    const { link, asked } = fakeGame();
    assert.equal(link.ask("player"), "");
    link.during(() => link.ask("player"));
    assert.equal(link.ask("player"), "player:#1");
    link.during(() => {
      link.tick();
    });
    // The tick renews the screen (always) and the player (asked before).
    assert.deepEqual(asked, ["player:", "player:", "screen:"]);
    assert.equal(link.ask("player"), "player:#2");
    assert.equal(link.ask("textWidth", "hi"), "");
  });

  it("does actions asked outside at the next tick, in order", () => {
    const { link, asked } = fakeGame();
    link.ask("show", "one");
    link.ask("say", "two");
    assert.deepEqual(asked, []);
    link.during(() => {
      link.tick();
    });
    assert.deepEqual(asked, ["show:one", "say:two", "screen:"]);
  });

  it("is empty before the game runs", () => {
    const link = new GameLink(
      () => undefined,
      () => undefined,
    );
    assert.equal(
      link.during(() => link.ask("fps")),
      "",
    );
  });
});

describe("a JS mod's header", () => {
  it("reads the @ lines at the top", () => {
    const header = parseHeader(
      "// @id coords\n// @name Coordinates\n// @version 2.1\n// @description Where you are.\napi.on('tick', () => {});\n// @name Not this",
      "whatever.js",
    );
    assert.deepEqual(header, { id: "coords", name: "Coordinates", version: "2.1", description: "Where you are." });
  });

  it("takes the name from the file and the id from the name", () => {
    assert.deepEqual(parseHeader("api.on('tick', () => {});", "My Cool Mod!.js"), {
      id: "my-cool-mod",
      name: "My Cool Mod!",
      version: "1.0",
      description: "",
    });
  });

  it("keeps ids to what storage keys allow", () => {
    assert.equal(toId("  Héllo / World  "), "h-llo-world");
    assert.equal(toId("***"), "mod");
  });
});

describe("colours", () => {
  it("takes numbers, opaque without alpha", () => {
    assert.equal(toColor(0xffcc00), "ffffcc00");
    assert.equal(toColor(0x80ff0000), "80ff0000");
  });

  it("takes CSS hex strings", () => {
    assert.equal(toColor("#fc0"), "ffffcc00");
    assert.equal(toColor("#00ff00"), "ff00ff00");
    assert.equal(toColor("#4000ff00"), "4000ff00");
  });

  it("falls back on anything else", () => {
    assert.equal(toColor("red"), "ffffffff");
    assert.equal(toColor(undefined, "80000000"), "80000000");
  });
});

describe("HUD commands", () => {
  it("writes a command a line, text last", () => {
    const commands = new HudCommands();
    commands.text("Hi\tthere\nyou", 2, 3.5, "#fff");
    commands.rect(0, 0, 10, 5);
    assert.equal(commands.toString(), "T\t2\t3.5\tffffffff\tHi there you\nF\t0\t0\t10\t5\t80000000");
  });

  it("closes what a mod left pushed", () => {
    const commands = new HudCommands();
    commands.push(10, 10, 2);
    commands.circle(0, 0, 4, 0xff0000);
    commands.pop();
    commands.pop();
    commands.push(1, 1);
    assert.equal(commands.toString(), "P\t10\t10\t2\nD\t0\t0\t4\tffff0000\nQ\nP\t1\t1\t1\nQ");
  });

  it("turns what isn't a number into 0", () => {
    const commands = new HudCommands();
    commands.text("x", "a", Number.NaN);
    assert.equal(commands.toString(), "T\t0\t0\tffffffff\tx");
  });
});
