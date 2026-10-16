/**
 * La planilla de la cartera: las columnas del CSV del contrato (seccion 6.6)
 * y una plantilla de ejemplo para descargar.
 */

export const COLUMNAS = [
  "deuda_id", "accion", "motivo_retiro", "deudor_rut", "deudor_tipo", "deudor_nombre", "deudor_correo",
  "deudor_telefono", "moneda", "concepto", "referencias", "cargo_concepto", "cargo_periodo", "cargo_monto",
  "cargo_vencimiento",
];

//  Una fila por cargo, de cualquier rubro: aca, los aranceles de un instituto.
//  Un cliente al dia va en una sola fila sin cargo, y un retiro sin deudor.
const EJEMPLO = [
  "AND-2025-0412;registrar;;21345678-4;persona;Benjamín Araya Toro;benjamin.araya@example.com;+56944120387;CLP;Arancel Técnico en Enfermería;matricula=AND-2025-0412;Arancel julio;2026-07;185000;2026-07-10",
  "AND-2025-0412;registrar;;21345678-4;persona;Benjamín Araya Toro;benjamin.araya@example.com;+56944120387;CLP;Arancel Técnico en Enfermería;matricula=AND-2025-0412;Arancel agosto;2026-08;185000;2026-08-10",
  "AND-2025-0412;registrar;;21345678-4;persona;Benjamín Araya Toro;benjamin.araya@example.com;+56944120387;CLP;Arancel Técnico en Enfermería;matricula=AND-2025-0412;Arancel septiembre;2026-09;185000;2026-09-10",
  "AND-2025-0298;registrar;;22134567-3;persona;Martín Fuentes Ríos;martin.fuentes@example.com;;CLP;Arancel Técnico en Enfermería;matricula=AND-2025-0298;;;;",
  "AND-2023-0177;retirar;pago_directo;;;;;;;;;;;;",
];

/** La plantilla, con BOM para que Excel respete las tildes. */
export function descargarPlantilla() {
  const texto = "﻿" + [COLUMNAS.join(";"), ...EJEMPLO].join("\r\n") + "\r\n";
  const url = URL.createObjectURL(new Blob([texto], { type: "text/csv;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = "cartera-plantilla.csv";
  a.click();
  URL.revokeObjectURL(url);
}

/** El id de una carga: uno por planilla elegida, para que confirmar dos veces no la cargue dos veces. */
export function idDeCarga(ahora = new Date()) {
  const dos = (n) => String(n).padStart(2, "0");
  return `CARGA-${ahora.getFullYear()}${dos(ahora.getMonth() + 1)}${dos(ahora.getDate())}`
    + `-${dos(ahora.getHours())}${dos(ahora.getMinutes())}${dos(ahora.getSeconds())}`;
}
