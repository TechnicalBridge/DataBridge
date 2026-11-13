const test = require("node:test");
const assert = require("node:assert/strict");

/**
 * Prueba la lógica de exportación y neutralización de fórmulas (CSV injection, #319, #444)
 */

function sanitizarParaExcel(valor) {
  if (valor === null || valor === undefined) return "";
  let texto = String(valor);
  const esNumero = typeof valor === "number" || /^-?\d+([.,]\d+)?$/.test(texto);
  if (!esNumero && /^[=+\-@\t\r]/.test(texto)) {
    texto = `'${texto}`;
  }
  return /[";\n]/.test(texto) ? `"${texto.replace(/"/g, '""')}"` : texto;
}

test("neutraliza valores de texto que comienzan con =, +, -, @, tabulador o retorno", () => {
  assert.equal(sanitizarParaExcel("=SUM(A1:A10)"), "'=SUM(A1:A10)");
  assert.equal(sanitizarParaExcel("+12345cmd"), "'+12345cmd");
  assert.equal(sanitizarParaExcel("-cmd|' /C calc'!A0"), "'-cmd|' /C calc'!A0");
  assert.equal(sanitizarParaExcel("@SUM(1+1)"), "'@SUM(1+1)");
  assert.equal(sanitizarParaExcel("\tvalorConTab"), "'\tvalorConTab");
  assert.equal(sanitizarParaExcel("\rvalorConRetorno"), "'\rvalorConRetorno");
});

test("preserva montos y números legítimos sin anteponer apóstrofo", () => {
  assert.equal(sanitizarParaExcel(150000), "150000");
  assert.equal(sanitizarParaExcel("150000"), "150000");
  assert.equal(sanitizarParaExcel(-5000), "-5000");
  assert.equal(sanitizarParaExcel("-5000"), "-5000");
  assert.equal(sanitizarParaExcel("12,50"), "12,50");
  assert.equal(sanitizarParaExcel("-12,50"), "-12,50");
  assert.equal(sanitizarParaExcel(0), "0");
});

test("escapa comillas dobles, puntos y comas y saltos de línea", () => {
  assert.equal(sanitizarParaExcel('Texto con "comillas"'), '"Texto con ""comillas"""');
  assert.equal(sanitizarParaExcel("Texto;con;puntoycoma"), '"Texto;con;puntoycoma"');
  assert.equal(sanitizarParaExcel("Texto\ncon salto"), '"Texto\ncon salto"');
});
