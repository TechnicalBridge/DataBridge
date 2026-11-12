// =============================================================================
//  7. Scalability testing: un escalon a la vez.
// =============================================================================
//  Tres escenarios secuenciales de 10, 25 y 50 usuarios (NIVELES_ESCALABILIDAD).
//  Cada uno dura 25 segundos y tiene su propia etiqueta, asi el resumen muestra
//  la latencia de cada nivel por separado en vez de un promedio que esconde el
//  punto donde empieza a crecer.
//
//  El recorrido es corto a proposito: lista, detalle, simulacion y /api/me.
// =============================================================================
import exec from 'k6/execution';
import { exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor } from '../modules/auth.js';
import { datosDeDeudas, recorridoCorto } from '../modules/journey.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.scalability;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  return datosDeDeudas(deudor);
}

export default function (datos) {
  recorridoCorto(datos, 'esc', { nivel: exec.scenario.name });
}

export function handleSummary(data) {
  return resumen(data, 'scalability');
}
