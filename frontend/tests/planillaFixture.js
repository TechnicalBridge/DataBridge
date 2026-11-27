import { zipSync, strToU8 } from "fflate";
import { COLUMNAS } from "../src/utils/plantillaCartera";

export const CSV = COLUMNAS.join(";") + "\nCTR-1;retirar;pago_directo;;;;;;;;;;;;\n";
const ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
const rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
const pack = "http://schemas.openxmlformats.org/package/2006";
const base = "application/vnd.openxmlformats-officedocument.spreadsheetml.";
const hoja = nombres => `<worksheet xmlns="${ns}"><sheetData><row r="1">${nombres.map((n, i) =>
  `<c r="${String.fromCharCode(65 + i)}1" t="inlineStr"><is><t>${n}</t></is></c>`).join("")}</row></sheetData></worksheet>`;

export function partesXlsx() {
  return {
    "[Content_Types].xml": `<Types xmlns="${pack}/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="${base}sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="${base}worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="${base}worksheet+xml"/></Types>`,
    "_rels/.rels": `<Relationships xmlns="${pack}/relationships"><Relationship Id="rId1" Type="${rel}/officeDocument" Target="xl/workbook.xml"/></Relationships>`,
    "xl/workbook.xml": `<workbook xmlns="${ns}" xmlns:r="${rel}"><sheets><sheet name="Deudores" sheetId="1" r:id="rId1"/><sheet name="Deudas" sheetId="2" r:id="rId2"/></sheets></workbook>`,
    "xl/_rels/workbook.xml.rels": `<Relationships xmlns="${pack}/relationships"><Relationship Id="rId1" Type="${rel}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="${rel}/worksheet" Target="worksheets/sheet2.xml"/></Relationships>`,
    "xl/worksheets/sheet1.xml": hoja(["RUT", "Tipo", "Nombre", "Correo", "Teléfono"]),
    "xl/worksheets/sheet2.xml": hoja(["Id de la deuda", "RUT del deudor", "Acción", "Moneda", "Concepto de la deuda", "Mes", "Monto", "Vencimiento", "Concepto del mes", "Tasa de interés mensual", "Motivo del retiro", "Referencias"])
      .replace("</sheetData>", '<row r="2"><c r="A2" t="inlineStr"><is><t>CTR-1</t></is></c><c r="C2" t="inlineStr"><is><t>retirar</t></is></c><c r="K2" t="inlineStr"><is><t>pago_directo</t></is></c></row></sheetData>'),
  };
}

export function zip(partes) {
  return zipSync(Object.fromEntries(Object.entries(partes).map(([nombre, datos]) => [nombre, strToU8(datos)])));
}

export const XLSX = zip(partesXlsx());

export function archivo(nombre, datos = CSV, tipo = "") {
  const bytes = typeof datos === "string" ? new TextEncoder().encode(datos) : datos;
  const file = new File([bytes], nombre, { type: tipo });
  // jsdom no implementa Blob.arrayBuffer; el navegador usa su File nativo.
  Object.defineProperty(file, "arrayBuffer", { configurable: true, value: async () => bytes.slice().buffer });
  return file;
}
