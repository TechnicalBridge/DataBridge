const test = require("node:test");
const assert = require("node:assert/strict");
const { EQUIPO, planificar, reconciliar } = require("../.github/scripts/repartir-por-turnos.cjs");
const issue = (number, extra = {}) => ({ number, title: "Tarea", created_at: "2026-11-21T12:00:00Z", state: "open", assignees: [], ...extra });

for (const cantidad of [1, 2, 3, 4, 10, 150]) {
  test(`${cantidad} issues simultáneos conservan orden y turnos`, () => {
    const lista = Array.from({ length: cantidad }, (_, n) => issue(n + 1));
    const esperado = lista.map((i, n) => ({ numero: i.number, responsable: EQUIPO[n % 4] }));
    assert.deepEqual(planificar(lista.reverse()), esperado);
  });
}

test("preserva responsables manuales y excluye PR, decisiones, retro y épicas", () => {
  const datos = [issue(1, { assignees: [{ login: EQUIPO[1] }] }),
    issue(2, { title: "Decisión: prueba" }), issue(3, { title: "retro: prueba" }),
    issue(4, { title: "Épica: prueba" }), issue(5, { pull_request: {} }), issue(6)];
  assert.deepEqual(planificar(datos), [{ numero: 6, responsable: EQUIPO[2] }]);
});

test("asignaciones fuera de orden y reintentos conservan el plan", () => {
  const datos = Array.from({ length: 10 }, (_, n) => issue(n + 1));
  const original = planificar(datos);
  for (const paso of [...original].reverse()) {
    datos.find(i => i.number === paso.numero).assignees = [{ login: paso.responsable }];
    for (const pendiente of planificar(datos)) assert.deepEqual(pendiente, original[pendiente.numero - 1]);
  }
  assert.deepEqual(planificar(datos), []);
});

test("recupera fallo temporal, ejecución perdida y más de cien registros", async () => {
  const datos = Array.from({ length: 150 }, (_, n) => issue(n + 1));
  let fallar = true;
  const api = {
    listar: async p => datos.slice((p - 1) * 100, p * 100),
    obtener: async n => datos[n - 1],
    asignar: async (n, login) => {
      if (n === 3 && fallar) { fallar = false; throw new Error("API temporal"); }
      datos[n - 1].assignees = [{ login }];
      return datos[n - 1];
    },
  };
  await assert.rejects(reconciliar(api), /API temporal/);
  await reconciliar(api);
  await reconciliar(api);
  assert.ok(datos.every((i, n) => i.assignees[0].login === EQUIPO[n % 4]));
});
