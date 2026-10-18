// =============================================================================
//  Cuánto aguanta DataBridge leyendo.
// =============================================================================
//  Sube la carga por escalones —5, 20, 50 y 100 usuarios a la vez— sobre las
//  cuatro lecturas que de verdad se usan, y anota en qué escalón la latencia
//  se dispara.
//
//  Pasa por el camino completo: nginx → gateway → servicio → MySQL. No le
//  pega directo a un servicio, porque medir un servicio sin su puerta de
//  entrada no dice nada de lo que siente una persona.
//
//  SOLO LECTURAS, a propósito. Un minuto de carga sostenida sobre "repactar"
//  dejaría miles de convenios basura en la base.
//
//      docker compose --profile carga run --rm k6 run /rendimiento/capacidad.js
// =============================================================================
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const PORTAL = __ENV.PORTAL_URI || 'http://portal:8080';
const AUTH = __ENV.AUTH_URI || 'http://ms-auth:8081';
const BUZON = (__ENV.MAIL_URI || '').trim().replace(/\/$/, '');
if (!BUZON) throw new Error('Falta MAIL_URI: capacidad.js necesita un buzon externo de prueba compatible con '
  + '/api/v1/messages. Docker Compose ya no incluye un buzon. No uses Gmail ni Brevo para esta carga.');
//  La clave interna llega del .env por el compose: no se escribe aqui.
const CLAVE_INTERNA = __ENV.INTERNAL_KEY;
if (!CLAVE_INTERNA) throw new Error('Falta INTERNAL_KEY: corre la prueba con el compose, que la toma del .env');
const RUT_DEUDOR = __ENV.RUT_DEUDOR || '16482337-7';
const CORREO_EMPRESA = __ENV.CORREO_EMPRESA || 'camila.reyes@apofyx.cl';

//  Si el limitador del gateway responde aunque sea una vez, la prueba mide el
//  limitador y no el sistema. Pasa facil: un "docker compose up" de cualquier
//  servicio sin la variable subida recrea el gateway con el cupo de fabrica.
//  Se detiene sola en vez de entregar un numero falso.
const limitador = new Counter('limitador_intervino');

//  Una medición por ruta: el promedio de todas juntas escondería que una es
//  diez veces más lenta que las otras.
const t = {
  lista: new Trend('ruta_lista', true),
  detalle: new Trend('ruta_detalle', true),
  simular: new Trend('ruta_simular', true),
  resumen: new Trend('ruta_resumen', true),
};

export const options = {
  scenarios: {
    lectura: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: 5 },
        { duration: '30s', target: 5 },
        { duration: '20s', target: 20 },
        { duration: '30s', target: 20 },
        { duration: '20s', target: 50 },
        { duration: '30s', target: 50 },
        { duration: '20s', target: 100 },
        { duration: '30s', target: 100 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  //  Si esto no se cumple, la prueba falla. Son los números que el README
  //  promete, no una aspiración.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    ruta_lista: ['p(95)<800'],
    ruta_detalle: ['p(95)<800'],
    ruta_simular: ['p(95)<800'],
    ruta_resumen: ['p(95)<1500'],
    limitador_intervino: [{ threshold: 'count<1', abortOnFail: true }],
  },
};

const json = (cuerpo) => ({ headers: { 'Content-Type': 'application/json' }, ...cuerpo });

/** Una sesión de deudor: se emite un código por la red interna y se canjea. */
function sesionDeudor() {
  const emision = http.post(`${AUTH}/internal/codigos`, JSON.stringify({
    rut: RUT_DEUDOR,
    canales: ['correo'],
    correo: 'carga@prueba.local',
    acreedor: 'Prueba de carga',
    paraQue: 'medicion',
  }), { headers: { 'Content-Type': 'application/json', 'X-Internal-Key': CLAVE_INTERNA } });

  if (emision.status !== 200) fail(`no se pudo emitir el código: ${emision.status} ${emision.body}`);

  const entrada = http.post(`${PORTAL}/api/auth/acceso`,
    JSON.stringify({ rut: RUT_DEUDOR, codigo: emision.json('codigo') }), json());
  if (entrada.status !== 200) fail(`el deudor no pudo entrar: ${entrada.status} ${entrada.body}`);
  return entrada.json('token');
}

/**
 * Una sesión de empresa. El enlace llega al buzón de prueba, así que se pide
 * y se lee de ahí: es el mismo camino que recorre una persona.
 */
function sesionEmpresa() {
  const antes = Date.now();
  const pedido = http.post(`${PORTAL}/api/auth/enlace`,
    JSON.stringify({ correo: CORREO_EMPRESA }), json());
  if (pedido.status !== 202) fail(`no se pudo pedir el acceso de empresa: ${pedido.status}`);

  //  El correo no es instantáneo: se espera a que llegue, sin pasar de ahí.
  for (let intento = 0; intento < 20; intento++) {
    sleep(0.5);
    const bandeja = http.get(`${BUZON}/api/v1/messages?limit=5`);
    if (bandeja.status !== 200) continue;
    for (const mensaje of bandeja.json('messages') || []) {
      if (new Date(mensaje.Created).getTime() < antes - 5000) continue;
      const cuerpo = http.get(`${BUZON}/api/v1/message/${mensaje.ID}`);
      const texto = `${cuerpo.body}`;
      const ficha = texto.match(/magic\?token=([0-9a-f-]{36})/);
      if (!ficha) continue;
      const canje = http.post(`${PORTAL}/api/auth/verify`,
        JSON.stringify({ token: ficha[1] }), json());
      if (canje.status === 200) return canje.json('token');
    }
  }
  fail('el acceso de empresa no llego al buzon externo de prueba: no se medira una carga parcial');
}

export function setup() {
  const deudor = sesionDeudor();
  const cabeceras = { headers: { Authorization: `Bearer ${deudor}` } };

  const lista = http.get(`${PORTAL}/api/debts`, cabeceras);
  if (lista.status !== 200) fail(`no se pudo leer la cartera: ${lista.status}`);
  //  HAL: la lista viene en _embedded.debts, y sin deudas no viene _embedded.
  const deudas = lista.json('_embedded.debts') || [];
  if (deudas.length === 0) fail('el deudor de la prueba no tiene deudas: no hay nada que medir');

  const empresa = sesionEmpresa();
  return { deudor, empresa, deuda: deudas[0].id };
}

export default function (datos) {
  const deudor = { headers: { Authorization: `Bearer ${datos.deudor}` } };

  const lista = http.get(`${PORTAL}/api/debts`, { ...deudor, tags: { ruta: 'lista' } });
  t.lista.add(lista.timings.duration);
  if (lista.status === 429) limitador.add(1);
  check(lista, { 'la cartera responde 200': (r) => r.status === 200 });

  const detalle = http.get(`${PORTAL}/api/debts/${datos.deuda}`, { ...deudor, tags: { ruta: 'detalle' } });
  t.detalle.add(detalle.timings.duration);
  if (detalle.status === 429) limitador.add(1);
  check(detalle, { 'el detalle responde 200': (r) => r.status === 200 });

  //  Entre 3 y 24 cuotas, que es lo que ofrece el simulador.
  const meses = 3 + Math.floor(Math.random() * 22);
  const simular = http.get(`${PORTAL}/api/debts/${datos.deuda}/simulate?months=${meses}`,
    { ...deudor, tags: { ruta: 'simular' } });
  t.simular.add(simular.timings.duration);
  if (simular.status === 429) limitador.add(1);
  check(simular, { 'el simulador responde 200': (r) => r.status === 200 });

  if (datos.empresa) {
    const resumen = http.get(`${PORTAL}/api/analytics/summary`, {
      headers: { Authorization: `Bearer ${datos.empresa}` }, tags: { ruta: 'resumen' },
    });
    t.resumen.add(resumen.timings.duration);
    if (resumen.status === 429) limitador.add(1);
    check(resumen, { 'el resumen responde 200': (r) => r.status === 200 });
  }

  //  Una persona mirando su deuda no pide cuatro páginas por segundo.
  sleep(1);
}
