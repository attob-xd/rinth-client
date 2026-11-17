// @id coords-hud
// @name Coordinates HUD
// @version 1.0
// @description Your position, facing and FPS in the top left.

const FACING = ["South", "West", "North", "East"];

api.on("hud", (draw) => {
  const player = api.player();
  if (player === null) {
    return;
  }
  const facing = FACING[Math.round((((player.heading % 360) + 360) % 360) / 90) % 4];
  const lines = [
    `XYZ ${player.x.toFixed(1)} ${player.y.toFixed(1)} ${player.z.toFixed(1)}`,
    `Facing ${facing}`,
    `${api.fps()} FPS`,
  ];
  const width = Math.max(...lines.map((line) => draw.textWidth(line))) + 8;
  draw.rect(2, 2, width, lines.length * 10 + 4, 0x80000000);
  lines.forEach((line, i) => draw.text(line, 6, 5 + i * 10, "#ffffff"));
});
