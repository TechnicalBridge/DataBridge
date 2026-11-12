// =============================================================================
//  Pruebas de búsqueda con omisión de tildes y diacríticos en Pagos (#431):
//  node --test pruebas/pagos-busqueda.test.cjs
// =============================================================================
const { test } = require('node:test');
const assert = require('node:assert/strict');

// Misma lógica de normalización que frontend/src/utils/formato.js
const paraBuscar = (texto) => String(texto ?? "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase()
  .replace(/[.\-\s]/g, "");

// Lógica de filtrado de PagosRecibidos.jsx
function filtrarPagos(pagos, buscar) {
  const texto = paraBuscar(buscar);
  if (!pagos || !texto) return pagos || [];
  return pagos.filter((p) => [p.deudor, p.deudorRut, p.externalId, p.referencia, p.acreedor].some((v) =>
    paraBuscar(v).includes(texto)
  ));
}

function contarPorMedio(buscados, medios) {
  return Object.fromEntries(medios.map((m) => [
    m,
    m === "todas" ? buscados.length : buscados.filter((p) => p.pasarela === m).length
  ]));
}

const PAGOS_PRUEBA = [
  {
    id: 1,
    deudor: "Panadería La Espiga",
    deudorRut: "76.123.456-K",
    externalId: "CTR-2025-001",
    referencia: "wp-1001",
    acreedor: "Patrimonio Inmuebles",
    pasarela: "webpay",
    monto: 150000
  },
  {
    id: 2,
    deudor: "Alarcón y Compañía SpA",
    deudorRut: "77.987.654-3",
    externalId: "CTR-2025-002",
    referencia: "kp-2002",
    acreedor: "Energía Austral",
    pasarela: "khipu",
    monto: 85000
  },
  {
    id: 3,
    deudor: "Felipe Rojas Muñoz",
    deudorRut: "16.482.337-7",
    externalId: "CTR-2025-003",
    referencia: "mp-3003",
    acreedor: "Patrimonio Inmuebles",
    pasarela: "mercadopago",
    monto: 346666
  }
];

const MEDIOS = ["todas", "webpay", "khipu", "mercadopago"];

test('escribe "deria" sin tilde y filtra correctamente "Panadería La Espiga"', () => {
  const resultados = filtrarPagos(PAGOS_PRUEBA, "deria");
  assert.equal(resultados.length, 1);
  assert.equal(resultados[0].deudor, "Panadería La Espiga");
});

test('escribe "rcon" o "alarcon" sin tilde y encuentra a "Alarcón y Compañía SpA"', () => {
  const porRcon = filtrarPagos(PAGOS_PRUEBA, "rcon");
  assert.equal(porRcon.length, 1);
  assert.equal(porRcon[0].deudor, "Alarcón y Compañía SpA");

  const porAlarcon = filtrarPagos(PAGOS_PRUEBA, "alarcon");
  assert.equal(porAlarcon.length, 1);
  assert.equal(porAlarcon[0].deudor, "Alarcón y Compañía SpA");
});

test('la búsqueda es insensible a mayúsculas, minúsculas y tildes', () => {
  const conMayus = filtrarPagos(PAGOS_PRUEBA, "PANADERIA");
  const conTilde = filtrarPagos(PAGOS_PRUEBA, "panadería");
  const conMinus = filtrarPagos(PAGOS_PRUEBA, "espiga");
  assert.equal(conMayus.length, 1);
  assert.equal(conTilde.length, 1);
  assert.equal(conMinus.length, 1);
  assert.equal(conMayus[0].id, 1);
  assert.equal(conTilde[0].id, 1);
  assert.equal(conMinus[0].id, 1);
});

test('ignora puntos, guiones y espacios en la búsqueda de RUT o referencia', () => {
  const porRutSinFormato = filtrarPagos(PAGOS_PRUEBA, "164823377");
  assert.equal(porRutSinFormato.length, 1);
  assert.equal(porRutSinFormato[0].deudor, "Felipe Rojas Muñoz");

  const porRutConPuntos = filtrarPagos(PAGOS_PRUEBA, "16.482.337");
  assert.equal(porRutConPuntos.length, 1);

  const porRefEspacios = filtrarPagos(PAGOS_PRUEBA, "ctr 2025 002");
  assert.equal(porRefEspacios.length, 1);
  assert.equal(porRefEspacios[0].deudor, "Alarcón y Compañía SpA");
});

test('permite buscar por referencia de la pasarela y por acreedor', () => {
  const porRef = filtrarPagos(PAGOS_PRUEBA, "wp-1001");
  assert.equal(porRef.length, 1);
  assert.equal(porRef[0].id, 1);

  const porAcreedor = filtrarPagos(PAGOS_PRUEBA, "Energia Austral");
  assert.equal(porAcreedor.length, 1);
  assert.equal(porAcreedor[0].id, 2);
});

test('el conteo de pagos por medio (cuantos) se actualiza dinámicamente', () => {
  const buscadosPatrimonio = filtrarPagos(PAGOS_PRUEBA, "Patrimonio");
  assert.equal(buscadosPatrimonio.length, 2);

  const conteos = contarPorMedio(buscadosPatrimonio, MEDIOS);
  assert.equal(conteos.todas, 2);
  assert.equal(conteos.webpay, 1);
  assert.equal(conteos.khipu, 0);
  assert.equal(conteos.mercadopago, 1);
});

test('al vaciar el campo de búsqueda se restauran todos los pagos', () => {
  const vacio = filtrarPagos(PAGOS_PRUEBA, "");
  assert.equal(vacio.length, PAGOS_PRUEBA.length);

  const conEspacios = filtrarPagos(PAGOS_PRUEBA, "   ");
  assert.equal(conEspacios.length, PAGOS_PRUEBA.length);
});

test('búsqueda sin coincidencias retorna lista vacía', () => {
  const sinResultados = filtrarPagos(PAGOS_PRUEBA, "inexistente999");
  assert.equal(sinResultados.length, 0);
});
