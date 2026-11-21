const test = require("node:test");
const assert = require("node:assert/strict");

test("fecha muestra el día sin cero inicial", async () => {
  const { fecha } = await import("../frontend/src/utils/formato.js");
  for (const [iso, dia] of [["2026-09-01", "1"], ["2026-11-09", "9"], ["2026-09-10", "10"]]) {
    assert.equal(fecha(iso).match(/^\d+/)?.[0], dia, iso);
  }
});

test("fecha y fechaHora conservan la zona local cerca del cambio de día", async () => {
  const { fecha, fechaHora } = await import("../frontend/src/utils/formato.js");
  for (const iso of ["2026-09-01T00:15:00Z", "2026-11-10T23:45:00Z"]) {
    const d = new Date(iso);
    assert.equal(fecha(iso), d.toLocaleDateString("es-CL", {
      day: "numeric", month: "short", year: "numeric",
    }));
    assert.equal(fechaHora(iso), d.toLocaleString("es-CL", {
      day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit",
    }));
  }
});

test("fechas sin hora conservan el mes y día local", async () => {
  const { fecha, fechaHora } = await import("../frontend/src/utils/formato.js");
  for (const iso of ["2026-09-01", "2026-11-09", "2026-11-10"]) {
    assert.equal(fecha(iso), new Date(`${iso}T12:00:00`).toLocaleDateString("es-CL", {
      day: "numeric", month: "short", year: "numeric",
    }));
  }
  for (const valor of [null, undefined, ""]) assert.equal(fechaHora(valor), "");
});

test("fecha devuelve texto vacío cuando no hay fecha", async () => {
  const { fecha } = await import("../frontend/src/utils/formato.js");
  for (const valor of [null, undefined, ""]) {
    assert.equal(fecha(valor), "");
  }
});
