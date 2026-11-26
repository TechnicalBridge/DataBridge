// =============================================================================
//  3. Spike testing: el golpe repentino.
// =============================================================================
//  Tres fases en el mismo ensayo:
//
//    base    5 usuarios durante 30 s
//    pico    salto a 150 usuarios en 5 s, 15 s sostenidos y vuelta
//    despues 5 usuarios otros 20 s
//
//  El umbral del pico admite mas latencia: un golpe asi puede encolar. Lo que
//  no se perdona es que despues del pico el sistema siga lento o con errores.
// =============================================================================
import exec from 'k6/execution';
import { exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor } from '../modules/auth.js';
import { datosDeDeudas } from '../modules/journey.js';
import { listar, obtener } from '../modules/debts.js';
import { esDetalleDeuda, esListaDeudas } from '../utils/checks.js';
import { medir } from '../utils/http.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.spike;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  const datos = datosDeDeudas(deudor);
  //  Calentamiento fuera de las metricas.
  listar(deudor);
  obtener(deudor, datos.deuda.id);
  return datos;
}

export default function (datos) {
  const fase = exec.scenario.name;
  medir('spike_lista', listar(datos.deudor), {
    valida: esListaDeudas,
    etiquetas: { fase },
  });
  medir('spike_detalle', obtener(datos.deudor, datos.deuda.id), {
    valida: esDetalleDeuda,
    etiquetas: { fase },
  });
}

export function handleSummary(data) {
  return resumen(data, 'spike');
}
