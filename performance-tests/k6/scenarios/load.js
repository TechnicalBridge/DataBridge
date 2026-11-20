// =============================================================================
//  1. Load testing: la carga esperada de un dia normal.
// =============================================================================
//  Usuarios concurrentes con pausa entre paginas, por el camino completo
//  (nginx -> gateway -> servicio -> MySQL). Sube a 5 y luego a MAX_VUS, y se
//  queda un minuto en la meseta alta para que el numero no sea de una rampa.
//
//  Las rutas medidas son las que usa el portal: lista, detalle, simulador,
//  pagos, /api/me, resumen de empresa y salud del asistente. El chat solo
//  entra si INCLUIR_IA=true; con un proveedor real la latencia no es comparable.
//
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/scenarios/load.js
// =============================================================================
import { opciones } from '../config/scenarios.js';
import { exigirEntornoDePruebas } from '../config/env.js';
import { sesionDeudor, sesionEmpresaObligatoria } from '../modules/auth.js';
import { datosDeDeudas, recorridoCompleto } from '../modules/journey.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.load;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  const empresa = sesionEmpresaObligatoria();
  return datosDeDeudas(deudor, empresa);
}

export default function (datos) {
  recorridoCompleto(datos, 'load', {
    pausa: 1,
    incluirResumen: true,
    incluirIA: true,
  });
}

export function handleSummary(data) {
  return resumen(data, 'load');
}
