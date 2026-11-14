const test = require("node:test");
const assert = require("node:assert/strict");

test("fecha muestra el día sin cero inicial", async () => {
  const { fecha } = await import("../frontend/src/utils/formato.js");
  for (const [iso, dia] of [["2026-09-01", "1"], ["2026-11-07", "7"], ["2026-09-25", "25"]]) {
    assert.equal(fecha(iso).match(/^\d+/)?.[0], dia, iso);
  }
});

test("fecha devuelve texto vacío cuando no hay fecha", async () => {
  const { fecha } = await import("../frontend/src/utils/formato.js");
  for (const valor of [null, undefined, ""]) {
    assert.equal(fecha(valor), "");
  }
});
