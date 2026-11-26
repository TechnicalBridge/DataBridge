import test from "node:test";
import assert from "node:assert/strict";
import { comoError } from "../src/api/client.js";

const SIN_SERVIDOR = "No pudimos conectarnos con DataBridge. Intenta de nuevo en unos minutos.";

test("respuestas HTML 500/502/504 y caídas de red muestran una indicación en español", () => {
  for (const status of [500, 502, 504]) {
    const error = comoError({ response: { status, data: "<html>Bad gateway</html>" },
      message: `Request failed with status code ${status}` });
    assert.equal(error.message, SIN_SERVIDOR);
    assert.equal(error.status, status);
  }
  assert.equal(comoError({ message: "Network Error" }).message, SIN_SERVIDOR);
  assert.equal(comoError({ response: { status: 503, data: { error: " " } } }).message, SIN_SERVIDOR);
});

test("conserva los mensajes y detalles reales del backend", () => {
  assert.equal(comoError({ response: { status: 409, data: { error: "Ya existe" } } }).message, "Ya existe");
  const error = comoError({ response: { status: 400,
    data: { error: { mensaje: "Planilla inválida", detalles: ["Fila 2"] } } } });
  assert.equal(error.message, "Planilla inválida");
  assert.deepEqual(error.detalles, ["Fila 2"]);
  assert.equal(comoError({ response: { status: 500, data: { error: "Error del servicio" } } }).message,
    "Error del servicio");
});
