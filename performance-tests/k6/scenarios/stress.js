// =============================================================================
//  2. Stress testing: donde empieza a doler.
// =============================================================================
//  Impone un ritmo creciente de peticiones por segundo sobre GET /api/debts,
//  la lectura mas usada, sin pausas. Sube 200, 500, 1.000 y 1.500 por segundo.
//
//  No busca un numero bonito: busca el escalon donde el p95 pasa de dos
//  segundos. Cuando eso ocurre, el umbral aborta la prueba para no seguir
//  recalentando la maquina; k6 termina con codigo distinto de cero y eso es el
//  resultado esperado, no una falla de la suite. Si aparecen 429, se detiene:
//  estaria midiendo el limitador del gateway y no ms-debt.
//
//      $env:RATE_GLOBAL_CAPACITY = "10000000"
//      docker compose --profile app up -d --wait gateway portal ms-debt
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/scenarios/stress.js
// =============================================================================
import { exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor } from '../modules/auth.js';
import { listar } from '../modules/debts.js';
import { esListaDeudas } from '../utils/checks.js';
import { medir } from '../utils/http.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.stress;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  //  Calentamiento: deja la JVM, el pool de MySQL y nginx listos antes de medir.
  listar(deudor);
  return { deudor };
}

export default function (datos) {
  medir('stress_lista', listar(datos.deudor), { valida: esListaDeudas });
}

export function handleSummary(data) {
  return resumen(data, 'stress');
}
