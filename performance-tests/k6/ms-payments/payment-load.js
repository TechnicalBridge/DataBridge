// =============================================================================
//  Cuánto aguanta DataBridge abriendo cobros.
// =============================================================================
//  Lo que pasa cuando muchos deudores tocan "Pagar" a la vez: abrir el cobro
//  (nginx → gateway → ms-payments, que le pregunta el monto a ms-debt y lo
//  guarda en MySQL) y la consulta que repite el portal mientras espera a la
//  pasarela. Sube por escalones —5, 10 y 20 deudores a la vez, o hasta MAX_VUS—.
//
//  SOLO CONTRA LAS PASARELAS SIMULADAS. Los ambientes de prueba de Transbank,
//  Khipu y Mercado Pago son compartidos: no se les hace carga. Antes de
//  empezar, la prueba abre un cobro de cada pasarela y se detiene si alguno
//  sale a una pasarela de verdad.
//
//  No confirma los cobros: pagarlos dejaría la deuda en cero a mitad de la
//  medición. Quedan abiertos y simulados en la base; al final se imprime cómo
//  borrarlos.
//
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/ms-payments/payment-load.js
// =============================================================================
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { PORTAL, RUT_DEUDOR, json, opciones, sesionDeudor } from '../config/options.js';

const PASARELAS = ['webpay', 'khipu', 'mercadopago'];

//  Lo mismo que en debt-load.js: si el limitador responde, se mide el
//  limitador y no el sistema. Se detiene en vez de dar un número falso.
const limitador = new Counter('limitador_intervino');
const t = {
  abrir: new Trend('ruta_abrir_cobro', true),
  consultar: new Trend('ruta_consultar_cobro', true),
};

export const options = opciones.paymentLoad;

function abrir(token, deuda, pasarela) {
  return http.post(`${PORTAL}/api/payments/checkout`,
    JSON.stringify({ debtId: deuda, gateway: pasarela }),
    json({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      tags: { ruta: 'abrir' } }));
}

/** Que el cobro se quede en casa: la página simulada de DataBridge, nunca una pasarela real. */
function esSimulado(cobro) {
  const url = `${cobro.json('checkoutUrl') || ''}`;
  return cobro.json('simulada') === true && !/transbank|khipu\.com|mercadopago|mercadolibre/i.test(url);
}

export function setup() {
  const inicio = new Date().toISOString();
  const token = sesionDeudor();
  const lista = http.get(`${PORTAL}/api/debts`, { headers: { Authorization: `Bearer ${token}` } });
  if (lista.status !== 200) fail(`no se pudo leer la cartera: ${lista.status}`);
  const deudas = lista.json('_embedded.debts') || [];

  //  Una deuda que se pueda pagar de una vez, y las tres pasarelas simuladas.
  for (const deuda of deudas) {
    const prueba = abrir(token, deuda.id, 'webpay');
    if (prueba.status !== 200 && prueba.status !== 201) continue;
    for (const pasarela of PASARELAS) {
      const cobro = pasarela === 'webpay' ? prueba : abrir(token, deuda.id, pasarela);
      if (!esSimulado(cobro)) {
        fail(`${pasarela} cobra de verdad (${cobro.json('checkoutUrl')}): esta prueba no se corre contra `
          + 'una pasarela real. Levanta la pila con TRANSBANK_ENVIRONMENT=SIMULADA y sin KHIPU_LLAVE ni '
          + 'MERCADOPAGO_ACCESS_TOKEN.');
      }
    }
    return { token, deuda: deuda.id, inicio };
  }
  fail('ninguna deuda del deudor de prueba se puede pagar ahora');
}

export default function (datos) {
  const pasarela = PASARELAS[Math.floor(Math.random() * PASARELAS.length)];
  const cobro = abrir(datos.token, datos.deuda, pasarela);
  t.abrir.add(cobro.timings.duration);
  if (cobro.status === 429) limitador.add(1);
  const abierto = check(cobro, {
    'el cobro se abre': (r) => r.status === 200 || r.status === 201,
    'y es simulado': (r) => esSimulado(r),
  });
  if (!abierto) return;

  //  El portal pregunta en qué va el cobro mientras la pasarela trabaja.
  const autorizado = { headers: { Authorization: `Bearer ${datos.token}` }, tags: { ruta: 'consultar' } };
  for (let i = 0; i < 2; i++) {
    sleep(1);
    const consulta = http.get(`${PORTAL}/api/payments/${cobro.json('id')}`, autorizado);
    t.consultar.add(consulta.timings.duration);
    if (consulta.status === 429) limitador.add(1);
    check(consulta, { 'la consulta responde 200': (r) => r.status === 200 });
  }
  sleep(2);
}

export function teardown(datos) {
  console.log('Los cobros de esta prueba quedaron abiertos y simulados. Para borrarlos:');
  console.log(`  DELETE e FROM payment_events e JOIN payments p ON p.id = e.payment_id `
    + `WHERE p.debtor_rut = '${RUT_DEUDOR}' AND p.status = 'created' AND p.created_at >= '${datos.inicio.replace('T', ' ').slice(0, 19)}';`);
  console.log(`  DELETE FROM payments WHERE debtor_rut = '${RUT_DEUDOR}' AND status = 'created' `
    + `AND created_at >= '${datos.inicio.replace('T', ' ').slice(0, 19)}';`);
}
