import { describe, it, expect } from "vitest";
import { validarPlanilla } from "../src/utils/validarPlanilla";
import { archivo, CSV, XLSX, partesXlsx, zip } from "./planillaFixture";

describe("validación temprana de CSV y XLSX", () => {
  it.each([
    ["cartera.csv", CSV, "text/csv"], ["cartera.CSV", "\ufeff" + CSV, "text/plain"],
    ["cartera.respaldo.csv", CSV, "application/octet-stream"],
    ["cartera.csv", CSV.replaceAll(";", '";"').replace("deuda_id", '"deuda_id').replace("cargo_vencimiento\n", 'cargo_vencimiento"\n'), ""],
    ["cartera.xlsx", XLSX, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"],
    ["cartera.XLSX", XLSX, "application/octet-stream"],
  ])("acepta %s con contenido del formato", async (nombre, bytes, tipo) => {
    await expect(validarPlanilla(archivo(nombre, bytes, tipo))).resolves.toBeUndefined();
  });

  it.each([
    ["cartera.csv.exe", CSV], ["cartera.exe.csv", CSV], ["cartera.sh.xlsx", XLSX],
    ["cartera.html", CSV], ["cartera.xlsm", XLSX], ["cartera.csv", ""],
    ["cartera.csv", "<html>contenido ajeno</html>"], ["cartera.csv", new Uint8Array([0, 1, 2, 3])],
    ["cartera.csv", new Uint8Array([255, 254])], ["cartera.xlsx", CSV],
    ["cartera.csv", XLSX], ["cartera.xlsx", zip({ "nota.txt": "no es Excel" })],
    ["cartera.xlsx", XLSX.slice(0, 40)], ["cartera.csv", CSV, "image/png"],
  ])("rechaza %s vacío, prohibido o incompatible", async (nombre, bytes, tipo) => {
    await expect(validarPlanilla(archivo(nombre, bytes, tipo))).rejects.toThrow(/archivo|planilla/i);
  });

  it.each([
    ["hoja ausente", p => { delete p["xl/worksheets/sheet1.xml"]; }],
    ["XML de hoja inválido", p => { p["xl/worksheets/sheet1.xml"] = "<worksheet>"; }],
    ["XML ajeno al formato", p => { p["xl/worksheets/sheet1.xml"] = "<html/>"; }],
    ["sin relaciones", p => { delete p["xl/_rels/workbook.xml.rels"]; }],
    ["relación externa", p => { p["xl/_rels/workbook.xml.rels"] = p["xl/_rels/workbook.xml.rels"].replace('Target="worksheets/sheet1.xml"', 'TargetMode="External" Target="https://example.com/hoja"'); }],
    ["libro con macros", p => { p["[Content_Types].xml"] = p["[Content_Types].xml"].replace("spreadsheetml.sheet.main+xml", "ms-excel.sheet.macroEnabled.main+xml"); }],
    ["entidades XML", p => { p["xl/workbook.xml"] = '<!DOCTYPE workbook [<!ENTITY x "x">]>' + p["xl/workbook.xml"]; }],
    ["relación fuera del paquete", p => { p["_rels/.rels"] = p["_rels/.rels"].replace('Target="xl/workbook.xml"', 'Target="../workbook.xml"'); }],
  ])("rechaza un ZIP con %s", async (_, alterar) => {
    const partes = partesXlsx(); alterar(partes);
    await expect(validarPlanilla(archivo("cartera.xlsx", zip(partes)))).rejects.toThrow(/formato/);
  });

  it("rechaza el tamaño excesivo antes de leer bytes", async () => {
    const file = { name: "cartera.csv", size: 5 * 1024 * 1024 + 1, arrayBuffer: () => { throw new Error("No debe leer"); } };
    await expect(validarPlanilla(file)).rejects.toThrow(/5 MB/);
  });
});
