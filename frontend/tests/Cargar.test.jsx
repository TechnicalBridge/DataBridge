import React from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import Cargar from "../src/pages/Cargar";
import { archivo, CSV, XLSX, zip } from "./planillaFixture";
import { opcionesDeCarga, revisarCartera, cargarCartera } from "../src/api/deudas";

vi.mock("../src/api/deudas", () => ({ opcionesDeCarga: vi.fn(), revisarCartera: vi.fn(),
  cargarCartera: vi.fn(), descargarPlantillaExcel: vi.fn() }));
const resultado = { aceptadas: 1, rechazadas: 0, resultados: [{ id_externo: "CTR-1", resultado: "retirada" }] };
let erroresReact;
beforeEach(() => {
  vi.clearAllMocks();
  opcionesDeCarga.mockResolvedValue({ acreedores: [{ rut: "76418902-7", nombre: "Empresa QA",
    campanas: [{ idExterno: "QA", nombre: "Campaña QA", estado: "en_curso" }] }] });
  revisarCartera.mockResolvedValue({ cartera: resultado, entrarian: [] });
  cargarCartera.mockResolvedValue(resultado);
  erroresReact = vi.spyOn(console, "error").mockImplementation(() => {});
});
afterEach(() => { cleanup(); expect(erroresReact).not.toHaveBeenCalled(); });

async function abrir() {
  const vista = render(<MemoryRouter initialEntries={["/databridge/cargar?campana=QA"]}><Cargar /></MemoryRouter>);
  await screen.findByText("Revisar la planilla");
  return vista;
}
function elegir(vista, metodo, file) {
  if (metodo === "selector") fireEvent.change(vista.container.querySelector('input[type="file"]'), { target: { files: [file] } });
  else fireEvent.drop(vista.container.querySelector(".dropzone"), { dataTransfer: { files: [file] } });
}

describe.each(["selector", "drag-and-drop"])("carga por %s", metodo => {
  it.each([["cartera.csv", CSV], ["cartera.xlsx", XLSX]])("conserva revisión y confirmación para %s", async (nombre, bytes) => {
    const vista = await abrir(); const file = archivo(nombre, bytes);
    elegir(vista, metodo, file);
    await waitFor(() => expect(screen.getByText("Revisar la planilla").disabled).toBe(false));
    expect(revisarCartera).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("Revisar la planilla"));
    await screen.findByText("Esto pasaría al cargarla");
    expect(revisarCartera.mock.calls[0][0].get("archivo")).toBe(file);
    fireEvent.click(screen.getByText("Confirmar"));
    await screen.findByRole("heading", { name: "Listo" });
    expect(cargarCartera.mock.calls[0][0].get("archivo")).toBe(file);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it.each([["cartera.exe.csv", CSV], ["cartera.csv.exe", CSV], ["cartera.csv", ""],
    ["cartera.xlsx", CSV], ["cartera.csv", "<html>ajeno</html>"], ["cartera.xlsx", zip({ "nota.txt": "ZIP" })]])(
    "bloquea %s sin solicitudes y permite reemplazarlo", async (nombre, bytes) => {
      const vista = await abrir();
      elegir(vista, metodo, archivo(nombre, bytes));
      expect((await screen.findByRole("alert")).textContent).toMatch(/archivo|planilla/i);
      const revisar = screen.getByText("Revisar la planilla");
      expect(revisar.disabled).toBe(true); fireEvent.click(revisar);
      expect(revisarCartera).not.toHaveBeenCalled(); expect(cargarCartera).not.toHaveBeenCalled();
      elegir(vista, metodo, archivo("otra.csv"));
      await waitFor(() => expect(screen.getByText("Revisar la planilla").disabled).toBe(false));
      expect(screen.queryByRole("alert")).toBeNull();
      fireEvent.click(screen.getByText("Revisar la planilla"));
      await screen.findByText("Esto pasaría al cargarla");
      fireEvent.click(screen.getByText("Confirmar"));
      await screen.findByRole("heading", { name: "Listo" });
      expect(revisarCartera).toHaveBeenCalledTimes(1); expect(cargarCartera).toHaveBeenCalledTimes(1);
    });
});

it("una lectura antigua no restaura el archivo después de rechazar la selección nueva", async () => {
  const vista = await abrir(); let completar;
  const lento = archivo("lento.csv");
  Object.defineProperty(lento, "arrayBuffer", { configurable: true, value: () => new Promise(r => { completar = r; }) });
  elegir(vista, "selector", lento);
  elegir(vista, "drag-and-drop", archivo("rechazado.exe.csv"));
  await screen.findByRole("alert");
  await act(async () => completar(new TextEncoder().encode(CSV).buffer));
  await waitFor(() => expect(screen.getByText("Revisar la planilla").disabled).toBe(true));
  expect(revisarCartera).not.toHaveBeenCalled();
});

it("no reemplaza la planilla durante una revisión ni duplica peticiones", async () => {
  const vista = await abrir(); let responder;
  revisarCartera.mockImplementationOnce(() => new Promise(r => { responder = r; }));
  const file = archivo("original.csv"); elegir(vista, "selector", file);
  await waitFor(() => expect(screen.getByText("Revisar la planilla").disabled).toBe(false));
  fireEvent.click(screen.getByText("Revisar la planilla"));
  elegir(vista, "drag-and-drop", archivo("otra.csv"));
  await act(async () => responder({ cartera: resultado, entrarian: [] }));
  await screen.findByText("Esto pasaría al cargarla");
  fireEvent.click(screen.getByText("Confirmar"));
  fireEvent.click(screen.getByText("Cargando…"));
  await screen.findByRole("heading", { name: "Listo" });
  expect(revisarCartera).toHaveBeenCalledTimes(1); expect(cargarCartera).toHaveBeenCalledTimes(1);
  expect(cargarCartera.mock.calls[0][0].get("archivo")).toBe(file);
});

it.each(["Enter", " "])("el selector permite activación por teclado con %s", async key => {
  const vista = await abrir();
  const selector = screen.getByRole("button", { name: "Elegir archivo" });
  const clic = vi.spyOn(vista.container.querySelector('input[type="file"]'), "click");
  selector.focus(); expect(document.activeElement).toBe(selector);
  fireEvent.keyDown(selector, { key }); expect(clic).toHaveBeenCalledTimes(1);
  expect(revisarCartera).not.toHaveBeenCalled();
});
