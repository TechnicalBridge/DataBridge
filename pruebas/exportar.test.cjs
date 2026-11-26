const { test } = require("node:test");
const assert = require("node:assert/strict");
const modulo = import("../frontend/src/utils/exportar.js");

test("neutraliza fórmulas y controles sin confiar en texto numérico", async () => {
  const { sanitizarParaExcel: s } = await modulo;
  for (const texto of ["=1+1", "+1+1", "-1+1", "@SUMA(1;1)", "-5000", "-12,50", "  =1+1", "\u0000+1+1", "\tvalor", "\rvalor", "\nvalor"]) {
    assert.ok(s(texto).replace(/^"/, "").startsWith("'"), JSON.stringify(texto));
  }
});

test("montos controlados CLP y UF conservan valores sumables", async () => {
  const { sanitizarParaExcel: s, montoParaExcel: m } = await modulo;
  assert.equal(s(m(-5000)), "-5000");
  assert.equal(s(m(-12.5, "UF")), "-12,50");
  assert.equal(s(m(12.5, "UF")), "12,50");
  assert.equal(s(m(0)), "0");
  assert.equal(s(-5000), "-5000");
  assert.equal(s(m("=1+1")), "");
  assert.equal(s(m(Infinity)), "");
});

test("escapa separadores, comillas y saltos sin perder UTF-8", async () => {
  const { sanitizarParaExcel: s } = await modulo;
  assert.equal(s('José;"sí"'), '"José;""sí"""');
  assert.equal(s("a\rb"), '"a\rb"');
  assert.equal(s("a\nb"), '"a\nb"');
  assert.equal(s(null), "");
  assert.equal(s(undefined), "");
});

test("descarga CSV con BOM y celdas protegidas", async () => {
  const { descargarCsv, montoParaExcel } = await modulo;
  let blob;
  const anterior = { document: global.document, create: URL.createObjectURL, revoke: URL.revokeObjectURL };
  global.document = { createElement: () => ({ click() {} }) };
  URL.createObjectURL = (valor) => { blob = valor; return "blob:prueba"; };
  URL.revokeObjectURL = () => {};
  try {
    descargarCsv("prueba.csv", ["Texto", "Monto"], [["=1+1", montoParaExcel(-12.5, "UF")]]);
    const bytes = new Uint8Array(await blob.arrayBuffer());
    assert.deepEqual([...bytes.slice(0, 3)], [239, 187, 191]);
    assert.equal(new TextDecoder().decode(bytes), "Texto;Monto\r\n'=1+1;-12,50\r\n");
  } finally {
    global.document = anterior.document;
    URL.createObjectURL = anterior.create;
    URL.revokeObjectURL = anterior.revoke;
  }
});
