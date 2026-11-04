// =============================================================================
//  El gateway como puerta de entrada.
// =============================================================================
//  Mide lo que cuesta enrutar, no lo que cuesta cada servicio: una petición a
//  cada ruta, con la misma sesión, para ver si alguna familia de rutas se
//  queda pegada. Las cuatro rutas van a servicios distintos:
//
//      GET /api/me         → ms-auth
//      GET /api/debts      → ms-debt
//      GET /api/payments   → ms-payments
//      GET /api/ai/salud   → ms-ai (pública)
//
//  Va directo a gateway:8082, sin nginx, porque lo que se quiere aislar es el
//  gateway. El camino completo con nginx lo mide ms-debt/debt-load.js.
//
//      $env:RATE_GLOBAL_CAPACITY = "10000000"
//      docker compose --profile app up -d gateway
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/gateway/gateway-load.js
// =============================================================================
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { GATEWAY, opciones, sesionDeudor } from '../config/options.js';

//  Si el limitador del gateway responde, la prueba mide el limitador y no el
//  enrutamiento. Se detiene sola en vez de entregar un número falso.
const limitador = new Counter('limitador_intervino');

const t = {
  me: new Trend('gw_me', true),
  deudas: new Trend('gw_debts', true),
  pagos: new Trend('gw_payments', true),
  salud: new Trend('gw_salud', true),
};

export const options = opciones.gatewayLoad;

export function setup() {
  const token = sesionDeudor();
  const sesion = { headers: { Authorization: `Bearer ${token}` } };

  //  Calentamiento: la primera conexión a cada servicio puede pagar DNS, pool
  //  y JIT. Se deja fuera de los Trend para que el p95 sea de régimen estable.
  for (const ruta of ['/api/me', '/api/debts', '/api/payments', '/api/ai/salud']) {
    const respuesta = http.get(`${GATEWAY}${ruta}`, sesion);
    if (respuesta.status !== 200) fail(`el gateway no enruta ${ruta}: ${respuesta.status} ${respuesta.body}`);
  }
  return { token };
}

export default function (datos) {
  const sesion = { headers: { Authorization: `Bearer ${datos.token}` } };
  const rutas = [
    { ruta: '/api/me', trend: t.me, etiqueta: 'me' },
    { ruta: '/api/debts', trend: t.deudas, etiqueta: 'deudas' },
    { ruta: '/api/payments', trend: t.pagos, etiqueta: 'pagos' },
    { ruta: '/api/ai/salud', trend: t.salud, etiqueta: 'salud' },
  ];

  for (const { ruta, trend, etiqueta } of rutas) {
    const respuesta = http.get(`${GATEWAY}${ruta}`, { ...sesion, tags: { ruta: etiqueta } });
    trend.add(respuesta.timings.duration);
    if (respuesta.status === 429) limitador.add(1);
    check(respuesta, { [`${ruta} responde 200`]: (r) => r.status === 200 });
  }

  //  Una persona no dispara cuatro rutas en bucle sin pensar.
  sleep(0.5);
}
