/**
 * Descargas hechas en el navegador: archivos que el servidor manda (PDF) y
 * planillas que se arman aca con lo que ya esta en pantalla.
 */

/** Guarda un Blob con ese nombre, como si fuera un enlace de descarga. */
export function descargar(blob, nombre) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = nombre;
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * Prepara una celda para CSV/Excel: neutraliza valores que Excel interpretaría como fórmulas
 * (CSV injection, #319) anteponiendo un apóstrofo si empieza con =, +, -, @, tabulador o retorno,
 * a menos que sea un número legítimo. Escapa comillas y separadores según RFC 4180.
 */
const montosControlados = new WeakSet();

export function sanitizarParaExcel(valor) {
  if (valor === null || valor === undefined) return "";
  let texto = String(valor);
  const esNumero = (typeof valor === "number" && Number.isFinite(valor)) || montosControlados.has(valor);
  if (!esNumero && (/^[\t\r\n]/.test(texto) || /^[\s\u0000-\u001f\u007f]*[=+\-@]/u.test(texto))) {
    texto = `'${texto}`;
  }
  return /[";\r\n]/.test(texto) ? `"${texto.replace(/"/g, '""')}"` : texto;
}

/**
 * Una planilla para Excel: separada por punto y coma, que es lo que espera
 * Excel en Chile (la coma es el decimal), y con BOM para que respete las
 * tildes. Los montos van sin puntos de miles, para que Excel los lea como
 * numeros y se puedan sumar.
 */
export function descargarCsv(nombre, columnas, filas) {
  const lineas = [columnas.map(sanitizarParaExcel).join(";"), ...filas.map((fila) => fila.map(sanitizarParaExcel).join(";"))];
  descargar(new Blob(["\ufeff" + lineas.join("\r\n") + "\r\n"], { type: "text/csv;charset=utf-8" }), nombre);
}

/** Celda numérica controlada: solo se usa en filas CSV, sin miles y con coma decimal. */
export function montoParaExcel(valor, moneda) {
  const n = Number(valor);
  if (!Number.isFinite(n)) return "";
  const texto = moneda === "UF" ? n.toFixed(2).replace(".", ",") : String(Math.round(n));
  const celda = Object.freeze({ toString: () => texto });
  montosControlados.add(celda);
  return celda;
}
