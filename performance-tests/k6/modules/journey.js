// =============================================================================
//  Recorridos de lectura compartidos.
// =============================================================================
//  Simulan lo que hace una persona en el portal: mirar la lista, abrir una
//  deuda, simular cuotas, revisar sus pagos y, si hay sesion de empresa, el
//  resumen de la cartera. Cada operacion se mide con su nombre para que el
//  umbral de una ruta no quede escondido en el promedio.
// =============================================================================
import http from 'k6/http';
import { sleep } from 'k6';
import { API, INCLUIR_IA, PORTAL } from '../config/env.js';
import { chat, salud } from './ai.js';
import {
  elegirDeuda,
  historialPagos,
  listar,
  obtener,
  resumenEmpresa,
  simular,
} from './debts.js';
import { listarPagos } from './payments.js';
import { medir } from '../utils/http.js';
import {
  esChat,
  esDetalleDeuda,
  esHistorial,
  esListaDeudas,
  esListaPagos,
  esMe,
  esResumen,
  esSaludIA,
  esSimulacion,
} from '../utils/checks.js';

function sesion(token) {
  return { headers: { Authorization: `Bearer ${token}` } };
}

/**
 * Recorrido corto: lista, detalle, simulacion y /api/me.
 * Se usa en escalabilidad, donde el objetivo es comparar niveles y no inflar
 * el ritmo con rutas que no cambian la conclusion.
 */
export function recorridoCorto(datos, prefijo, etiquetas = {}) {
  medir(`${prefijo}_lista`, listar(datos.deudor), {
    valida: esListaDeudas,
    etiquetas,
  });
  medir(`${prefijo}_detalle`, obtener(datos.deudor, datos.deuda.id), {
    valida: esDetalleDeuda,
    etiquetas,
  });
  medir(`${prefijo}_simular`, simular(datos.deudor, datos.deuda.id, 6 + (__ITER % 12)), {
    valida: esSimulacion,
    etiquetas,
  });
  medir(`${prefijo}_me`, http.get(`${PORTAL}/api/me`, sesion(datos.deudor)), {
    valida: esMe,
    etiquetas,
  });
}

/**
 * Recorrido completo de lectura. `incluirResumen` e `incluirIA` permiten
 * apagar lo que dependa del entorno sin romper los umbrales de lo demas.
 */
export function recorridoCompleto(datos, prefijo, opciones = {}) {
  const {
    pausa = 0.5,
    incluirPagos = true,
    incluirResumen = true,
    incluirIA = true,
    incluirHistorial = false,
    etiquetas = {},
  } = opciones;

  medir(`${prefijo}_lista`, listar(datos.deudor), {
    valida: esListaDeudas,
    etiquetas,
  });
  medir(`${prefijo}_detalle`, obtener(datos.deudor, datos.deuda.id), {
    valida: esDetalleDeuda,
    etiquetas,
  });
  medir(`${prefijo}_simular`, simular(datos.deudor, datos.deuda.id, 3 + (__ITER % 22)), {
    valida: esSimulacion,
    etiquetas,
  });
  medir(`${prefijo}_me`, http.get(`${PORTAL}/api/me`, sesion(datos.deudor)), {
    valida: esMe,
    etiquetas,
  });
  if (incluirPagos) {
    medir(`${prefijo}_pagos`, listarPagos(datos.deudor), {
      valida: esListaPagos,
      etiquetas,
    });
  }
  if (incluirHistorial) {
    medir(`${prefijo}_historial`, historialPagos(datos.deudor), {
      valida: esHistorial,
      etiquetas,
    });
  }
  medir(`${prefijo}_ia_salud`, salud(API), {
    valida: esSaludIA,
    etiquetas,
  });
  if (INCLUIR_IA && incluirIA) {
    medir(`${prefijo}_ia_chat`, chat(datos.deudor), {
      valida: esChat,
      etiquetas,
    });
  }
  if (datos.empresa && incluirResumen) {
    medir(`${prefijo}_resumen`, resumenEmpresa(datos.empresa), {
      valida: esResumen,
      etiquetas,
    });
  }
  if (pausa > 0) sleep(pausa);
}

/** Prepara el objeto que reciben los recorridos. Falla si no hay deudas. */
export function datosDeDeudas(token, empresa = null, externalId = undefined) {
  const lista = listar(token);
  if (lista.status !== 200) {
    throw new Error(`no se pudo leer la cartera: ${lista.status} ${lista.body}`);
  }
  let deudas = [];
  try {
    deudas = lista.json('_embedded.debts') || [];
  } catch (error) {
    deudas = [];
  }
  const deuda = elegirDeuda(deudas, externalId);
  if (!deuda) {
    throw new Error('el deudor de la prueba no tiene deudas: no hay nada que medir');
  }
  return { deudor: token, empresa, deuda };
}
