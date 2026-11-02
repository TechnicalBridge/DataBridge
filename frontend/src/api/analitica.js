import { pedir } from "./client";

/** Los numeros de la cartera de la empresa de la sesion. */
export const resumenDeCartera = () => pedir("/analytics/summary");

/** Lo que la empresa le paga a DataBridge en un mes (2026-10): el modelo de negocio, #200. */
export const comisionDelMes = (mes) => pedir("/analytics/comision", { params: { mes } });
