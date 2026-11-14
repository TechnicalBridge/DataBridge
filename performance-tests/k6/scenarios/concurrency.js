// =============================================================================
//  6. Concurrency testing: las mismas operaciones, al mismo tiempo.
// =============================================================================
//  Dos escenarios que arrancan juntos:
//
//    consultas     20 VUs hacen lista, detalle y simulacion sin pausa
//    repactaciones 3 VUs aceptan un plan de 6 cuotas en deudas distintas
//
//  Las repactaciones son escrituras reales (anulan cuotas pendientes y crean
//  el nuevo plan), por eso son solo tres y se pueden apagar con
//  PERMITIR_ESCRITURAS=false. Se eligen deudores distintos para no pelear por
//  la misma fila.
//
//  No se confirma ningun pago.
// =============================================================================
import exec from 'k6/execution';
import { PERMITIR_ESCRITURAS, RUTS_CONCURRENCIA, exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor } from '../modules/auth.js';
import {
  elegirRepactable,
  listar,
  obtener,
  repactar as repactarDeuda,
  simular,
} from '../modules/debts.js';
import {
  esDetalleDeuda,
  esListaDeudas,
  esRepactacion,
  esSimulacion,
} from '../utils/checks.js';
import { medir } from '../utils/http.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.concurrency;

export function setup() {
  exigirEntornoDePruebas();
  const repactables = [];

  for (const rut of RUTS_CONCURRENCIA) {
    const token = sesionDeudor(rut);
    const respuesta = listar(token);
    let deudas = [];
    try {
      deudas = respuesta.json('_embedded.debts') || [];
    } catch (error) {
      deudas = [];
    }
    const deuda = elegirRepactable(deudas);
    if (deuda) repactables.push({ rut, token, deuda });
  }

  if (repactables.length === 0) {
    throw new Error('ninguno de los deudores de RUTS_CONCURRENCIA tiene una deuda repactable');
  }
  if (PERMITIR_ESCRITURAS && repactables.length < 3) {
    console.warn(`solo hay ${repactables.length} deuda(s) repactable(s): `
      + 'las repactaciones se repartiran entre las que haya');
  }

  return {
    repactables,
    deudor: repactables[0].token,
    deuda: repactables[0].deuda,
  };
}

export default function (datos) {
  const elegida = datos.repactables[exec.vu.idInTest % datos.repactables.length];
  const token = elegida.token;
  const deuda = elegida.deuda;

  medir('concurrencia_lista', listar(token), { valida: esListaDeudas });
  medir('concurrencia_detalle', obtener(token, deuda.id), { valida: esDetalleDeuda });
  medir('concurrencia_simular', simular(token, deuda.id, 6 + (__ITER % 12)), {
    valida: esSimulacion,
  });
}

export function repactar(datos) {
  const elegida = datos.repactables[exec.vu.idInTest % datos.repactables.length];
  const respuesta = repactarDeuda(elegida.token, elegida.deuda.id, 6);
  medir('concurrencia_repactar', respuesta, { valida: esRepactacion });
  if (respuesta.status === 409) {
    console.warn(`la deuda ${elegida.deuda.externalId} ya no se podia repactar (409); `
      + 'se conto como respuesta valida de concurrencia');
  }
}

export function handleSummary(data) {
  return resumen(data, 'concurrency');
}
