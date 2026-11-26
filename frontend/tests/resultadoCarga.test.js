import assert from "node:assert/strict";
import { test } from "node:test";
import { mensajesCarga } from "../src/utils/resultadoCarga.js";

const resultados = (...estados) => estados.map((resultado) => ({ resultado }));

for (const [estado, singular, plural] of [
  ["registrada", "1 deuda entró en Verano.", "2 deudas entraron en Verano."],
  ["retirada", "1 deuda salió de la cobranza.", "2 deudas salieron de la cobranza."],
  ["rechazada", "1 deuda no entró.", "2 deudas no entraron."],
  ["sin_cambios", "1 deuda quedó sin cambios.", "2 deudas quedaron sin cambios."],
]) {
  test(`distingue una y varias filas con resultado ${estado}`, () => {
    assert.deepEqual(mensajesCarga({ aceptadas: 99, resultados: resultados(estado) }, "Verano"), [singular]);
    assert.deepEqual(mensajesCarga({ resultados: resultados(estado, estado) }, "Verano"), [plural]);
  });
}

test("informa por separado todos los resultados de una planilla mixta", () => {
  const lote = { resultados: resultados("registrada", "actualizada", "retirada", "rechazada", "sin_cambios", "al_dia") };
  assert.deepEqual(mensajesCarga(lote), [
    "1 deuda entró en la campaña.",
    "1 deuda se actualizó en la campaña.",
    "1 deuda salió de la cobranza.",
    "1 deuda no entró.",
    "1 deuda quedó sin cambios.",
    "1 cliente al día no se guardó.",
  ]);
});

test("distingue un lote vacío de uno ya cargado", () => {
  assert.deepEqual(mensajesCarga({ resultados: [] }), ["La carga no produjo cambios."]);
  assert.deepEqual(mensajesCarga({ repetido: true, resultados: resultados("retirada") }),
    ["Esa planilla ya estaba cargada: no se duplicó nada."]);
});
