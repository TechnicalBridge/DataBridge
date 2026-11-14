// =============================================================================
//  12. Capacity testing: cuanto se sostiene de verdad.
// =============================================================================
//  Escalones de ritmo fijo sobre GET /api/debts: 50, 100, 200 y 400
//  peticiones por segundo (TASAS_CAPACIDAD). Cada escalon dura 20 segundos y
//  tiene su propia etiqueta.
//
//  La capacidad observada es el ultimo escalon que cumple el umbral estricto
//  de p95, errores y tasa de exito (hasta 200/s). Los escalones de arriba son
//  sondas de la rodilla: se les exige que no haya errores y que la latencia no
//  se dispare, no el SLO estricto. En el resumen se ven ademas las iteraciones
//  perdidas: si k6 no logra pedir el ritmo, ahi termina la capacidad real de
//  la maquina, no la del sistema.
// =============================================================================
import exec from 'k6/execution';
import { exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor } from '../modules/auth.js';
import { datosDeDeudas } from '../modules/journey.js';
import { listar } from '../modules/debts.js';
import { esListaDeudas } from '../utils/checks.js';
import { medir } from '../utils/http.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.capacity;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  return datosDeDeudas(deudor);
}

export default function (datos) {
  medir('capacity_lista', listar(datos.deudor), {
    valida: esListaDeudas,
    etiquetas: { nivel: exec.scenario.name },
  });
}

export function handleSummary(data) {
  return resumen(data, 'capacity');
}
