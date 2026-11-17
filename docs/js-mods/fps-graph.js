// @id fps-graph
// @name FPS Graph
// @version 1.0
// @description The last few seconds of frame rate, bottom right.

const samples = [];

api.every(5, () => {
  samples.push(api.fps());
  if (samples.length > 40) {
    samples.shift();
  }
});

api.on("hud", (draw) => {
  if (samples.length === 0) {
    return;
  }
  const width = 84;
  const height = 30;
  const left = draw.width - width - 4;
  const top = draw.height - height - 40;
  const highest = Math.max(60, ...samples);
  draw.rect(left, top, width, height, 0x80000000);
  samples.forEach((fps, i) => {
    const bar = Math.max(1, Math.round((fps / highest) * (height - 4)));
    const color = fps >= 60 ? "#55ff55" : fps >= 30 ? "#ffcc33" : "#ff5555";
    draw.rect(left + 2 + i * 2, top + height - 2 - bar, 1, bar, color);
  });
  draw.text(`${samples[samples.length - 1]} FPS`, left + 2, top - 10, "#ffffff");
});
