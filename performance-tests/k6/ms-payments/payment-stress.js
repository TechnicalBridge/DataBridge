// =============================================================================
//  Dónde empieza a doler en ms-payments leyendo.
// =============================================================================
//  payment-load.js mide abrir cobros, que escribe. Este mide la otra mitad:
//  el historial que el portal pide cada vez que alguien entra a "Mis pagos".
//  Impone un ritmo de peticiones por segundo, sin pausas, y lo sube por
//  escalones hasta que la latencia se dispara o k6 ya no sostiene el ritmo.
//
//  Una sola ruta —GET /api/payments— para que el número sea del servicio y no
//  de una mezcla. Solo lectura: no abre cobros ni confirma pagos, así que se
//  puede repetir sin dejar basura en la base.
//
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/ms-payments/payment-stress.js
// =============================================================================
import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';
import { PAYMENTS, RUT_DEUDOR, opciones, sesionDeudor } from '../config/options.js';

const t = new Trend('pagos_lista', true);

export const options = opciones.paymentStress;

export function setup() {
  const token = sesionDeudor(RUT_DEUDOR, 'pagos-estres@prueba.local', 'Prueba de estres de pagos');

  //  Calentamiento: la primera consulta paga el pool de conexiones y el JIT.
  const lista = http.get(`${PAYMENTS}/api/payments`, { headers: { Authorization: `Bearer ${token}` } });
  if (lista.status !== 200) fail(`GET /api/payments no responde 200: ${lista.status} ${lista.body}`);
  return { token };
}

export default function (datos) {
  const respuesta = http.get(`${PAYMENTS}/api/payments`, {
    headers: { Authorization: `Bearer ${datos.token}` }, tags: { ruta: 'lista' },
  });
  t.add(respuesta.timings.duration);
  check(respuesta, { 'GET /api/payments responde 200': (r) => r.status === 200 });
}
