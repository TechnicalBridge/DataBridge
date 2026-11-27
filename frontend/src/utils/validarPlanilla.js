import { COLUMNAS } from "./plantillaCartera";

const FORMATO = "El archivo debe ser una planilla .xlsx o un .csv del contrato, con el formato correspondiente";
const MAX_ARCHIVO = 5 * 1024 * 1024; // El mismo límite de ms-debt y nginx.
const MAX_XML = 32 * 1024 * 1024;
const MAX_PAQUETE = 64 * 1024 * 1024;
const EJECUTABLE = /\.(?:exe|com|bat|cmd|ps1|sh|js|mjs|cjs|html?|svg|php|py|jar|msi|dll|scr|vbs|vbe|wsf|hta)(?:\.|$)/i;
const CSV_TIPOS = ["text/csv", "application/csv", "text/plain", "application/vnd.ms-excel"];
const XLSX_TIPO = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const LIBRO_TIPO = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
const HOJA_TIPO = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml";
const REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
const REL_ESTRICTA = "http://purl.oclc.org/ooxml/officeDocument/relationships/";
const NS_HOJA = ["http://schemas.openxmlformats.org/spreadsheetml/2006/main", "http://purl.oclc.org/ooxml/spreadsheetml/main"];
const NS_PAQUETE = "http://schemas.openxmlformats.org/package/2006/";

function invalido() { throw new Error(FORMATO); }

function texto(bytes) {
  try { return new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
  catch { return invalido(); }
}

/** Solo el encabezado del contrato; las filas y reglas de negocio las valida el servidor. */
function validarCsv(bytes) {
  const contenido = texto(bytes);
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(contenido)) invalido();
  const columnas = [];
  let celda = "", comillas = false;
  for (let i = 0; i < contenido.length; i++) {
    const c = contenido[i];
    if (c === '"') {
      if (comillas && contenido[i + 1] === '"') { celda += '"'; i++; }
      else comillas = !comillas;
    } else if (!comillas && c === ";") { columnas.push(celda.trim().toLowerCase()); celda = ""; }
    else if (!comillas && (c === "\r" || c === "\n")) {
      if (!columnas.length && !celda.trim()) continue;
      break;
    } else celda += c;
  }
  columnas.push(celda.trim().toLowerCase());
  if (comillas || COLUMNAS.some(c => !columnas.includes(c))) invalido();
}

function xml(bytes, raiz, namespaces) {
  if (!bytes) return invalido();
  const contenido = texto(bytes);
  if (/<!DOCTYPE|<!ENTITY/i.test(contenido)) invalido();
  const doc = new DOMParser().parseFromString(contenido, "application/xml");
  const nodo = doc.documentElement;
  if (doc.getElementsByTagName("parsererror").length || nodo.localName !== raiz
      || !namespaces.includes(nodo.namespaceURI)) invalido();
  return nodo;
}

function elementos(nodo, nombre) { return [...nodo.getElementsByTagNameNS(nodo.namespaceURI, nombre)]; }
function relacionDe(nodo, tipo) { return nodo.getAttribute("Type") === REL + tipo || nodo.getAttribute("Type") === REL_ESTRICTA + tipo; }

/** Resuelve destinos de relaciones dentro del ZIP, sin rutas externas ni escapes del paquete. */
function ruta(base, destino) {
  if (!destino || /[\\:#?]/.test(destino)) return invalido();
  const partes = destino.startsWith("/") ? [] : base.split("/").slice(0, -1);
  for (const parte of destino.split("/")) {
    if (!parte || parte === ".") continue;
    if (parte === "..") { if (!partes.length) return invalido(); partes.pop(); }
    else partes.push(parte);
  }
  return partes.join("/");
}

async function validarXlsx(bytes) {
  const { unzipSync } = await import("fflate");
  const entradas = new Map();
  let total = 0;
  // Examinar el directorio y limitar tamaños ANTES de descomprimir. No basta con PK.
  const iniciales = unzipSync(bytes, { filter(entrada) {
    if (entradas.has(entrada.name) || entradas.size >= 4096 || entrada.originalSize > MAX_XML
        || (total += entrada.originalSize) > MAX_PAQUETE) invalido();
    entradas.set(entrada.name, entrada);
    return ["[Content_Types].xml", "_rels/.rels"].includes(entrada.name);
  } });
  function extraer(nombres) { return unzipSync(bytes, { filter: e => nombres.includes(e.name) }); }
  const tipos = xml(iniciales["[Content_Types].xml"], "Types", [NS_PAQUETE + "content-types"]);
  const overrides = elementos(tipos, "Override");
  const tipoDe = nombre => overrides.find(n => n.getAttribute("PartName") === "/" + nombre)?.getAttribute("ContentType");
  const relaciones = xml(iniciales["_rels/.rels"], "Relationships", [NS_PAQUETE + "relationships"]);
  const oficina = elementos(relaciones, "Relationship").find(n => relacionDe(n, "officeDocument"));
  if (!oficina || oficina.getAttribute("TargetMode") === "External") invalido();
  const libroRuta = ruta("", oficina.getAttribute("Target"));
  if (tipoDe(libroRuta) !== LIBRO_TIPO) invalido();
  const partes = libroRuta.split("/");
  partes.splice(-1, 0, "_rels");
  const relRuta = partes.join("/") + ".rels";
  const archivos = extraer([libroRuta, relRuta]);
  const libro = xml(archivos[libroRuta], "workbook", NS_HOJA);
  const relacionesLibro = elementos(xml(archivos[relRuta], "Relationships", [NS_PAQUETE + "relationships"]), "Relationship");
  const hojas = elementos(libro, "sheet").map(hoja => {
    const id = hoja.getAttributeNS(REL.slice(0, -1), "id") || hoja.getAttributeNS(REL_ESTRICTA.slice(0, -1), "id");
    const relacion = relacionesLibro.find(n => n.getAttribute("Id") === id && relacionDe(n, "worksheet"));
    if (!id || !relacion || relacion.getAttribute("TargetMode") === "External") return invalido();
    const nombre = ruta(libroRuta, relacion.getAttribute("Target"));
    if (!entradas.has(nombre) || tipoDe(nombre) !== HOJA_TIPO) return invalido();
    return nombre;
  });
  if (!hojas.length) invalido();
  const datos = extraer(hojas);
  for (const hoja of hojas) {
    if (!elementos(xml(datos[hoja], "worksheet", NS_HOJA), "sheetData").length) invalido();
  }
}

/** Comprobación temprana complementaria; ms-debt conserva la decisión autoritativa. */
export async function validarPlanilla(archivo) {
  if (!archivo || !/\.(csv|xlsx)$/i.test(archivo.name) || EJECUTABLE.test(archivo.name)) invalido();
  if (!archivo.size) throw new Error("El archivo está vacío. Elige una planilla con datos.");
  if (archivo.size > MAX_ARCHIVO) throw new Error("El archivo pasa del tamaño máximo permitido (5 MB).");
  const csv = /\.csv$/i.test(archivo.name);
  const tipo = (archivo.type || "").split(";")[0].trim().toLowerCase();
  if (tipo && tipo !== "application/octet-stream" && !(csv ? CSV_TIPOS.includes(tipo) : tipo === XLSX_TIPO)) invalido();
  try {
    const bytes = new Uint8Array(await archivo.arrayBuffer());
    if (csv) validarCsv(bytes);
    else await validarXlsx(bytes);
  } catch { invalido(); }
}
