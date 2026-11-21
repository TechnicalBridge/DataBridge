// =============================================================================
//  9. API performance testing: latencia, errores y throughput del gateway.
// =============================================================================
//  Ritmo constante contra gateway:8082, sin nginx, para medir el enrutamiento
//  y los servicios. Cada iteracion hace cinco lecturas y, cada cinco
//  iteraciones, el resumen de empresa; el chat entra cada cinco iteraciones si
//  INCLUIR_IA=true.
//
//  Ademas de los umbrales por ruta, exige un minimo de http_reqs por segundo:
//  si k6 no logra pedir el ritmo, el resultado no sirve para presumir
//  throughput.
// =============================================================================
import { API, exigirEntornoDePruebas, INCLUIR_IA } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor, sesionEmpresaObligatoria } from '../modules/auth.js';
import { chat, salud } from '../modules/ai.js';
import {
  listar,
  resumenEmpresa,
  simular,
} from '../modules/debts.js';
import { listarPagos } from '../modules/payments.js';
import { esChat, esListaDeudas, esListaPagos, esResumen, esSaludIA, esSimulacion } from '../utils/checks.js';
import { medir } from '../utils/http.js';
import { resumen } from '../utils/summary.js';
import http from 'k6/http';

export const options = opciones.api;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  const empresa = sesionEmpresaObligatoria();
  const lista = listar(deudor, API);
  if (lista.status !== 200) {
    throw new Error(`el gateway no entrega GET /api/debts: ${lista.status} ${lista.body}`);
  }
  const deudas = lista.json('_embedded.debts') || [];
  if (deudas.length === 0) throw new Error('el deudor de la prueba no tiene deudas');
  return { deudor, empresa, deuda: deudas[0] };
}

export default function (datos) {
  medir('api_me', http.get(`${API}/api/me`, {
    headers: { Authorization: `Bearer ${datos.deudor}` },
  }), { valida: (r) => r.status === 200 });

  medir('api_lista', listar(datos.deudor, API), { valida: esListaDeudas });
  medir('api_simular', simular(datos.deudor, datos.deuda.id, 6 + (__ITER % 12), API), {
    valida: esSimulacion,
  });
  medir('api_pagos', listarPagos(datos.deudor, API), { valida: esListaPagos });
  medir('api_ia_salud', salud(API), { valida: esSaludIA });

  if (__ITER % 5 === 0) {
    medir('api_resumen', resumenEmpresa(datos.empresa, API), { valida: esResumen });
    if (INCLUIR_IA) {
      medir('api_ia_chat', chat(datos.deudor, undefined, API), { valida: esChat });
    }
  }
}

export function handleSummary(data) {
  return resumen(data, 'api-performance');
}
