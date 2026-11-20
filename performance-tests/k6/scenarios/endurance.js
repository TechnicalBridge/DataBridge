// =============================================================================
//  4. Endurance testing: aguantar, no solo llegar.
// =============================================================================
//  Ritmo constante de 20 iteraciones por segundo durante 10 minutos (se puede
//  cambiar con DURACION_ENDURANCE y TASA_CARGA). Cada iteracion hace las
//  lecturas del deudor: lista, detalle, simulacion y /api/me.
//
//  Lo que se busca no es capacidad sino estabilidad: que el p95 no crezca con
//  el paso de los minutos y que no aparezcan errores ni fugas visibles en las
//  respuestas.
// =============================================================================
import { exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor } from '../modules/auth.js';
import { datosDeDeudas, recorridoCorto } from '../modules/journey.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.endurance;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  return datosDeDeudas(deudor);
}

export default function (datos) {
  recorridoCorto(datos, 'endurance', {});
}

export function handleSummary(data) {
  return resumen(data, 'endurance');
}
