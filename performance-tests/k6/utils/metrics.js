// =============================================================================
//  Metricas propias de la suite.
// =============================================================================
//  k6 ya entrega http_req_duration, http_req_failed y http_reqs. Estas agregan
//  la lectura por operacion y por fase, que es lo que permite poner umbrales
//  distintos a "listar deudas" y a "abrir un cobro" sin que el promedio de
//  todas las rutas esconda la que se cae.
//
//  Se etiquetan con `operacion` (deudas_lista, deudas_detalle, ...) y, cuando
//  aplica, con `fase` o `nivel`. Los umbrales pueden filtrar por esas
//  etiquetas, por ejemplo: suite_duracion{operacion:deudas_lista}.
// =============================================================================
import { Counter, Rate, Trend } from 'k6/metrics';

/** Tiempo de una peticion, en milisegundos. */
export const duracion = new Trend('suite_duracion', true);

/** Peticiones intentadas. Su tasa (count/segundo) es el throughput observado. */
export const peticiones = new Counter('suite_peticiones');

/** Peticiones que cumplieron la condicion de exito de su operacion. */
export const exitos = new Counter('suite_exitos');

/** Peticiones que no la cumplieron (incluye 4xx/5xx y respuestas incompletas). */
export const errores = new Counter('suite_errores');

/** Proporcion de peticiones exitosas sobre el total. */
export const tasaExito = new Rate('suite_tasa_exito');

/** Cada respuesta 429 del gateway. Si aparece, se esta midiendo el limitador. */
export const limitador = new Counter('limitador_intervino');

/** Cuantas iteraciones de carga de cartera se completaron por formato. */
export const volumen = new Counter('volumen_cargas');
