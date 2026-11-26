// =============================================================================
//  Envoltorio de peticiones con metricas y comprobaciones.
// =============================================================================
//  Cada operacion se mide dos veces: en suite_duracion (con su etiqueta) y en
//  las metricas propias de k6. Ademas se cuenta si salio bien, si fallo y si
//  el gateway respondio 429. La idea es que un escenario pueda decir "esto
//  cuenta como exito" (por ejemplo, un 409 al repactar una deuda ya pagada)
//  sin falsear el resultado.
// =============================================================================
import http from 'k6/http';
import { check } from 'k6';
import {
  duracion,
  errores,
  exitos,
  limitador,
  peticiones,
  tasaExito,
} from './metrics.js';
import { es2xx } from './checks.js';

export function medir(operacion, respuesta, opciones = {}) {
  const etiquetas = { operacion, ...(opciones.etiquetas || {}) };
  const valida = opciones.valida || es2xx;
  const correcta = valida(respuesta);

  duracion.add(respuesta.timings.duration, etiquetas);
  peticiones.add(1, etiquetas);
  tasaExito.add(correcta, etiquetas);
  if (correcta) {
    exitos.add(1, etiquetas);
  } else {
    errores.add(1, etiquetas);
  }
  if (respuesta.status === 429) {
    limitador.add(1, etiquetas);
  }

  check(respuesta, {
    [`${operacion}: respuesta valida`]: () => correcta,
  });
  return respuesta;
}

/** Una peticion suelta con su medicion. */
export function peticion(metodo, url, cuerpo, params, operacion, valida, etiquetas) {
  const respuesta = http.request(metodo, url, cuerpo, params || {});
  return medir(operacion, respuesta, { valida, etiquetas });
}

/** GET con sesion. */
export function getConSesion(url, token, operacion, valida, etiquetas) {
  return peticion('GET', url, null, {
    headers: { Authorization: `Bearer ${token}` },
  }, operacion, valida, etiquetas);
}

/** POST JSON con sesion. */
export function postJsonConSesion(url, cuerpo, token, operacion, valida, etiquetas) {
  return peticion('POST', url, JSON.stringify(cuerpo), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  }, operacion, valida, etiquetas);
}
