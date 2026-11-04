// =============================================================================
//  Cuánto aguanta ms-debt leyendo.
// =============================================================================
//  Sube la carga por escalones —5, 20, 50 y 100 usuarios a la vez— sobre las
//  cuatro lecturas de deuda que de verdad se usan, y anota en qué escalón la
//  latencia se dispara.
//
//  Entra por el portal a propósito: nginx → gateway → ms-debt → MySQL. Es el
//  camino completo que siente una persona; medir el servicio sin su puerta de
//  entrada diría poco de la experiencia real.
//
//  SOLO LECTURAS, a propósito. Un minuto de carga sostenida sobre "repactar"
//  dejaría miles de convenios basura en la base.
//
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/ms-debt/debt-load.js
// =============================================================================
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { PORTAL, opciones, sesionDeudor, sesionEmpresa } from '../config/options.js';

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

export const options = opciones.debtLoad;

export function setup() {
  const deudor = sesionDeudor();
  const cabeceras = { headers: { Authorization: `Bearer ${deudor}` } };

  const lista = http.get(`${PORTAL}/api/debts`, cabeceras);
  if (lista.status !== 200) fail(`no se pudo leer la cartera: ${lista.status}`);
  //  HAL: la lista viene en _embedded.debts, y sin deudas no viene _embedded.
  const deudas = lista.json('_embedded.debts') || [];
  if (deudas.length === 0) fail('el deudor de la prueba no tiene deudas: no hay nada que medir');

  const empresa = sesionEmpresa();
  if (!empresa) {
    console.warn('sin sesión de empresa: se salta /api/analytics/summary');
  }
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
