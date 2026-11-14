// =============================================================================
//  8. Response time testing: cuanto tarda cada operacion, sin ruido.
// =============================================================================
//  Un solo usuario recorre las operaciones principales diez veces
//  (ITERACIONES_RESPUESTA). No hay concurrencia, asi que lo que se mide es el
//  costo de cada ruta y no la cola que produce la carga.
//
//  El portal se mide aparte del API. El resumen de empresa se incluye porque
//  es la lectura mas pesada de ms-debt.
// =============================================================================
import http from 'k6/http';
import { exigirEntornoDePruebas, PORTAL } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor, sesionEmpresaObligatoria } from '../modules/auth.js';
import { datosDeDeudas, recorridoCompleto } from '../modules/journey.js';
import { medir } from '../utils/http.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.responseTime;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  const empresa = sesionEmpresaObligatoria();
  return datosDeDeudas(deudor, empresa);
}

export default function (datos) {
  medir('rt_portal', http.get(`${PORTAL}/`), {
    valida: (r) => r.status === 200 && `${r.body}`.includes('id="root"'),
  });
  recorridoCompleto(datos, 'rt', {
    pausa: 0.2,
    incluirResumen: true,
    incluirIA: true,
  });
}

export function handleSummary(data) {
  return resumen(data, 'response-time');
}
