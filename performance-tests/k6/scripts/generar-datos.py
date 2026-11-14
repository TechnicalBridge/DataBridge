#!/usr/bin/env python3
"""Genera las carteras ficticias que usan las pruebas de volumen.

No hay datos reales aca: los RUT se inventan con su digito verificador
calculado, los nombres son genericos y todos los correos terminan en
example.com (un dominio reservado para documentacion y pruebas).

Uso:
    python scripts/generar-datos.py
    python scripts/generar-datos.py --deudores 800
    python scripts/generar-datos.py --deudores 800 --meses 3

Escribe en performance-tests/k6/data:

    cartera-volumen.csv     una fila por cargo, separador ';'
    cartera-volumen.xlsx    hojas Deudores y Deudas, como la plantilla
    cartera-pequena.csv     tres deudores, para el humo
    cartera-pequena.xlsx    lo mismo en Excel

Las fechas son fijas y quedan en el pasado, para que la ingesta nunca las
rechace por el minimo de dias de mora. Los montos son ficticios.
"""

from __future__ import annotations

import argparse
import csv
from datetime import date
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill
from openpyxl.utils import get_column_letter

RAIZ = Path(__file__).resolve().parents[1]
DATOS = RAIZ / "data"

MESES = {
    1: ("ene", "enero"),
    2: ("feb", "febrero"),
    3: ("mar", "marzo"),
    4: ("abr", "abril"),
    5: ("may", "mayo"),
    6: ("jun", "junio"),
    7: ("jul", "julio"),
    8: ("ago", "agosto"),
    9: ("sep", "septiembre"),
    10: ("oct", "octubre"),
    11: ("nov", "noviembre"),
    12: ("dic", "diciembre"),
}

# Columnas exactas que espera CarteraExcel (ms-debt).
COLUMNAS_DEUDORES = ["RUT", "Tipo", "Nombre", "Correo", "Teléfono"]
COLUMNAS_DEUDAS = [
    "Id de la deuda",
    "RUT del deudor",
    "Acción",
    "Moneda",
    "Concepto de la deuda",
    "Mes",
    "Monto",
    "Vencimiento",
    "Concepto del mes",
    "Tasa de interés mensual",
    "Motivo del retiro",
    "Referencias",
]

COLUMNAS_CSV = [
    "deuda_id",
    "accion",
    "motivo_retiro",
    "deudor_rut",
    "deudor_tipo",
    "deudor_nombre",
    "deudor_correo",
    "deudor_telefono",
    "moneda",
    "concepto",
    "referencias",
    "tasa_interes_mensual",
    "cargo_concepto",
    "cargo_periodo",
    "cargo_monto",
    "cargo_vencimiento",
]


def digito_verificador(cuerpo: int) -> str:
    suma = 0
    multiplicador = 2
    for digito in reversed(str(cuerpo)):
        suma += int(digito) * multiplicador
        multiplicador = 2 if multiplicador == 7 else multiplicador + 1
    resto = 11 - (suma % 11)
    if resto == 11:
        return "0"
    if resto == 10:
        return "K"
    return str(resto)


def rut_ficticio(indice: int) -> str:
    """Un RUT valido, en el rango 30.000.000+, para no chocar con la demo."""
    cuerpo = 30_000_000 + indice
    return f"{cuerpo}-{digito_verificador(cuerpo)}"


def deudores(cantidad: int, meses: int) -> tuple[list[dict], list[dict]]:
    personas = []
    deudas = []
    for indice in range(cantidad):
        numero = indice + 1
        rut = rut_ficticio(indice)
        nombre = f"Deudor Volumen {numero:04d}"
        correo = f"deudor.volumen.{numero:04d}@example.com"
        personas.append(
            {
                "rut": rut,
                "tipo": "persona",
                "nombre": nombre,
                "correo": correo,
                "telefono": f"+569{numero:08d}"[:12],
            }
        )
        deuda_id = f"VOL-{numero:04d}"
        monto = 300_000 + (numero % 50) * 10_000
        for posicion in range(meses):
            # Fechas fijas de 2026, siempre vencidas y lejos del umbral de mora.
            mes = 1 + posicion
            anio = 2026
            fecha = date(anio, mes, 5)
            deudas.append(
                {
                    "deuda_id": deuda_id,
                    "rut": rut,
                    "nombre": nombre,
                    "correo": correo,
                    "telefono": personas[-1]["telefono"],
                    "moneda": "CLP",
                    "concepto": "Servicios ficticios de prueba",
                    "mes": f"{MESES[mes][0]}-{anio}",
                    "monto": monto,
                    "vencimiento_excel": fecha.strftime("%d-%m-%Y"),
                    "vencimiento_csv": fecha.isoformat(),
                    "periodo_csv": fecha.strftime("%Y-%m"),
                    "concepto_mes": f"Servicios {MESES[mes][1]} {anio}",
                    "referencias": f"contrato={deuda_id} | origen=rendimiento",
                }
            )
    return personas, deudas


def escribir_csv(ruta: Path, personas: list[dict], deudas: list[dict]) -> None:
    # La plantilla del contrato usa UTF-8 y separador ';'. Los datos no llevan
    # ';' a proposito, para no tener que citar campos.
    with ruta.open("w", encoding="utf-8", newline="") as archivo:
        escritor = csv.DictWriter(archivo, fieldnames=COLUMNAS_CSV, delimiter=";")
        escritor.writeheader()
        for deuda in deudas:
            escritor.writerow(
                {
                    "deuda_id": deuda["deuda_id"],
                    "accion": "registrar",
                    "motivo_retiro": "",
                    "deudor_rut": deuda["rut"],
                    "deudor_tipo": "persona",
                    "deudor_nombre": deuda["nombre"],
                    "deudor_correo": deuda["correo"],
                    "deudor_telefono": deuda["telefono"],
                    "moneda": deuda["moneda"],
                    "concepto": deuda["concepto"],
                    "referencias": deuda["referencias"],
                    "tasa_interes_mensual": "",
                    "cargo_concepto": deuda["concepto_mes"],
                    "cargo_periodo": deuda["periodo_csv"],
                    "cargo_monto": deuda["monto"],
                    "cargo_vencimiento": deuda["vencimiento_csv"],
                }
            )


def escribir_xlsx(ruta: Path, personas: list[dict], deudas: list[dict]) -> None:
    libro = Workbook()
    hoja_deudores = libro.active
    hoja_deudores.title = "Deudores"
    hoja_deudas = libro.create_sheet("Deudas")

    cabecera = Font(bold=True, color="FFFFFF")
    fondo = PatternFill("solid", fgColor="1F4E78")

    for columna, titulo in enumerate(COLUMNAS_DEUDORES, start=1):
        celda = hoja_deudores.cell(row=1, column=columna, value=titulo)
        celda.font = cabecera
        celda.fill = fondo
    for fila, persona in enumerate(personas, start=2):
        hoja_deudores.cell(row=fila, column=1, value=persona["rut"])
        hoja_deudores.cell(row=fila, column=2, value=persona["tipo"])
        hoja_deudores.cell(row=fila, column=3, value=persona["nombre"])
        hoja_deudores.cell(row=fila, column=4, value=persona["correo"])
        hoja_deudores.cell(row=fila, column=5, value=persona["telefono"])

    for columna, titulo in enumerate(COLUMNAS_DEUDAS, start=1):
        celda = hoja_deudas.cell(row=1, column=columna, value=titulo)
        celda.font = cabecera
        celda.fill = fondo
    for fila, deuda in enumerate(deudas, start=2):
        hoja_deudas.cell(row=fila, column=1, value=deuda["deuda_id"])
        hoja_deudas.cell(row=fila, column=2, value=deuda["rut"])
        hoja_deudas.cell(row=fila, column=3, value="registrar")
        hoja_deudas.cell(row=fila, column=4, value=deuda["moneda"])
        hoja_deudas.cell(row=fila, column=5, value=deuda["concepto"])
        hoja_deudas.cell(row=fila, column=6, value=deuda["mes"])
        hoja_deudas.cell(row=fila, column=7, value=deuda["monto"])
        hoja_deudas.cell(row=fila, column=8, value=deuda["vencimiento_excel"])
        hoja_deudas.cell(row=fila, column=9, value=deuda["concepto_mes"])
        hoja_deudas.cell(row=fila, column=10, value=None)
        hoja_deudas.cell(row=fila, column=11, value=None)
        hoja_deudas.cell(row=fila, column=12, value=deuda["referencias"])

    for hoja in (hoja_deudores, hoja_deudas):
        hoja.freeze_panes = "A2"
        for columna, titulo in enumerate(
            [c.value for c in hoja[1]], start=1
        ):
            hoja.column_dimensions[get_column_letter(columna)].width = max(
                14, min(38, len(str(titulo)) + 4)
            )

    libro.save(ruta)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--deudores",
        type=int,
        default=400,
        help="Cuantos deudores ficticios genera la cartera de volumen (400 por omision)",
    )
    parser.add_argument(
        "--meses",
        type=int,
        default=2,
        help="Cuantos meses impagos tiene cada deuda (2 por omision)",
    )
    args = parser.parse_args()
    if args.deudores < 1 or args.meses < 1:
        parser.error("--deudores y --meses tienen que ser mayores que cero")

    DATOS.mkdir(parents=True, exist_ok=True)
    personas, deudas = deudores(args.deudores, args.meses)
    escribir_csv(DATOS / "cartera-volumen.csv", personas, deudas)
    escribir_xlsx(DATOS / "cartera-volumen.xlsx", personas, deudas)

    personas_p, deudas_p = deudores(3, 2)
    escribir_csv(DATOS / "cartera-pequena.csv", personas_p, deudas_p)
    escribir_xlsx(DATOS / "cartera-pequena.xlsx", personas_p, deudas_p)

    print(
        f"Listo: {len(personas)} deudores, {len(deudas)} filas de deuda, "
        f"{args.meses} mes(es) por deuda"
    )
    for nombre in (
        "cartera-volumen.csv",
        "cartera-volumen.xlsx",
        "cartera-pequena.csv",
        "cartera-pequena.xlsx",
    ):
        ruta = DATOS / nombre
        print(f"  {ruta.relative_to(RAIZ)}  {ruta.stat().st_size / 1024:.1f} KiB")


if __name__ == "__main__":
    main()
