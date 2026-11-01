import { pedir } from "./client";

/** Los mandatos de la empresa (#234): { agencia, mandatos }. Una que cobra lo suyo no tiene. */
export const listarMandatos = () => pedir("/debts/mandatos");

/** La agencia declara que cobra por cuenta de un acreedor. */
export const declararMandato = (mandato) => pedir("/debts/mandatos", { method: "POST", body: mandato });

/** Una fecha de término de hoy en adelante, o null: sin fecha de término. */
export const renovarMandato = (id, hasta) => pedir(`/debts/mandatos/${id}/renovar`, { method: "POST", body: { hasta } });

/** Terminarlo desde ya: lo ya cargado se sigue cobrando. */
export const terminarMandato = (id) => pedir(`/debts/mandatos/${id}/terminar`, { method: "POST" });
