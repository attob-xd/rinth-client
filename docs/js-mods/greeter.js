// @id greeter
// @name Greeter
// @version 1.0
// @description Says hi when you join, counts your joins, and shows where you are when you press H.

api.on("join", (server) => {
  const joins = api.storage.get("joins", 0) + 1;
  api.storage.set("joins", joins);
  api.show(`Welcome${server === "" ? " to singleplayer" : " to " + server}! Join number ${joins}.`);
});

api.onKey("KeyH", () => {
  const player = api.player();
  if (player !== null) {
    api.show(`You're at ${Math.floor(player.x)}, ${Math.floor(player.y)}, ${Math.floor(player.z)} in ${player.dimension}.`);
  }
});
