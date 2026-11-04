// =============================================================================
//  Donde empieza a doler.
// =============================================================================
//  debt-load.js simula personas: cada una lee su deuda y se queda un segundo
//  mirando. Eso dice como lo siente alguien, pero no cuanto aguanta el sistema:
//  con esas pausas, cien personas son apenas unas cuatrocientas peticiones por
//  segundo, y ahi la latencia no se mueve.
//
//  Este escenario no simula a nadie. Impone un ritmo de peticiones por segundo
//  —sin pausas— y lo sube por escalones hasta que algo cede: la latencia se
//  dispara, aparecen errores, o k6 ya no alcanza a sostener el ritmo pedido
//  (las "iteraciones perdidas"). Ese escalon es el techo.
//
//  Una sola ruta, la mas usada —la lista de deudas del deudor—, para que el
//  numero sea del sistema y no de una mezcla de rutas.
//
//      $env:RATE_GLOBAL_CAPACITY = "100000"
//      docker compose --profile app up -d gateway
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/ms-debt/debt-stress.js
// =============================================================================
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { PORTAL, RUT_DEUDOR, opciones, sesionDeudor } from '../config/options.js';

//  Si el limitador del gateway responde aunque sea una vez, la prueba no esta
//  midiendo el sistema sino el limitador, y cualquier numero que entregue es
//  falso. Pasa facil: basta un "docker compose up" de cualquier servicio sin
//  la variable subida para que Compose recree el gateway con el cupo de
//  fabrica. Asi que la prueba se detiene sola en vez de mentir.
const limitador = new Counter('limitador_intervino');

export const options = opciones.debtStress;

export function setup() {
  return { token: sesionDeudor(RUT_DEUDOR, 'estres@prueba.local', 'Prueba de estres') };
}

export default function (datos) {
  const r = http.get(`${PORTAL}/api/debts`, {
    headers: { Authorization: `Bearer ${datos.token}` }, tags: { ruta: 'lista' },
  });
  if (r.status === 429) limitador.add(1);
  check(r, { 'responde 200': (x) => x.status === 200 });
}
