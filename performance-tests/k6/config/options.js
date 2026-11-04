// =============================================================================
//  Configuración compartida de las pruebas de rendimiento.
// =============================================================================
//  Las rutas y los cupos salen del entorno de Docker Compose. Los valores por
//  omisión son los mismos que usa la pila local.
//
//  Cada prueba vive en la carpeta del servicio que mide. Las que entran por el
//  portal (debt-load, debt-stress y payment-load) usan PORTAL; las que miden un
//  servicio por dentro usan su URI directa y no pasan por el gateway.
// =============================================================================
import http from 'k6/http';
import { fail, sleep } from 'k6';

export const PORTAL = __ENV.PORTAL_URI || 'http://portal:8080';
export const GATEWAY = __ENV.GATEWAY_URI || 'http://gateway:8082';
export const AUTH = __ENV.AUTH_URI || 'http://ms-auth:8081';
//  DEBT queda disponible para una prueba que aísle ms-debt; debt-load.js hoy
//  entra por el portal para medir el camino completo.
export const DEBT = __ENV.DEBT_URI || 'http://ms-debt:8083';
export const PAYMENTS = __ENV.PAYMENTS_URI || 'http://ms-payments:8084';
export const AI = __ENV.AI_URI || 'http://ms-ai:8085';
export const BUZON = __ENV.MAIL_URI || 'http://mailpit:8025';
export const RUT_DEUDOR = __ENV.RUT_DEUDOR || '16482337-7';
export const CORREO_EMPRESA = __ENV.CORREO_EMPRESA || 'camila.reyes@apofyx.cl';
export const CLAVE_INTERNA = __ENV.INTERNAL_KEY;
export const CUPO_AUTH = Number(__ENV.RATE_AUTH_CAPACITY || 10);
export const MAX_VUS = Number(__ENV.MAX_VUS || 20);
export const MAX_VUS_SERVICIO = Number(__ENV.MAX_VUS_SERVICIO || 50);

export const json = (extra = {}) => ({
  headers: { 'Content-Type': 'application/json' },
  ...extra,
});

export function requerirClaveInterna() {
  if (!CLAVE_INTERNA) {
    throw new Error('Falta INTERNAL_KEY: corre la prueba con el compose, que la toma del .env');
  }
  return CLAVE_INTERNA;
}

/** Una sesión de deudor: se emite un código por la red interna y se canjea. */
export function sesionDeudor(rut = RUT_DEUDOR, correo = 'carga@prueba.local', acreedor = 'Prueba de carga') {
  const emision = http.post(`${AUTH}/internal/codigos`, JSON.stringify({
    rut, canales: ['correo'], correo, acreedor, paraQue: 'medicion',
  }), { headers: { 'Content-Type': 'application/json', 'X-Internal-Key': requerirClaveInterna() } });
  if (emision.status !== 200) fail(`no se pudo emitir el código: ${emision.status} ${emision.body}`);

  const entrada = http.post(`${PORTAL}/api/auth/acceso`,
    JSON.stringify({ rut, codigo: emision.json('codigo') }), json());
  if (entrada.status !== 200) fail(`el deudor no pudo entrar: ${entrada.status} ${entrada.body}`);
  return entrada.json('token');
}

/**
 * Una sesión de empresa. El enlace llega al buzón de prueba, así que se pide
 * y se lee de ahí: es el mismo camino que recorre una persona.
 */
export function sesionEmpresa(correo = CORREO_EMPRESA) {
  const antes = Date.now();
  const pedido = http.post(`${PORTAL}/api/auth/enlace`, JSON.stringify({ correo }), json());
  if (pedido.status !== 202) return null;

  //  El correo no es instantáneo: se espera a que llegue, sin pasar de ahí.
  for (let intento = 0; intento < 20; intento++) {
    sleep(0.5);
    const bandeja = http.get(`${BUZON}/api/v1/messages?limit=5`);
    if (bandeja.status !== 200) continue;
    for (const mensaje of bandeja.json('messages') || []) {
      if (new Date(mensaje.Created).getTime() < antes - 5000) continue;
      const cuerpo = http.get(`${BUZON}/api/v1/message/${mensaje.ID}`);
      const ficha = `${cuerpo.body}`.match(/magic\?token=([0-9a-f-]{36})/);
      if (!ficha) continue;
      const canje = http.post(`${PORTAL}/api/auth/verify`,
        JSON.stringify({ token: ficha[1] }), json());
      if (canje.status === 200) return canje.json('token');
    }
  }
  return null;
}

//  Un freno de mano compartido: si el limitador del gateway responde, la prueba
//  está midiendo el limitador y no el sistema. Se detiene en vez de mentir.
const sinLimitador = {
  limitador_intervino: [{ threshold: 'count<1', abortOnFail: true }],
};

//  Escalones de usuarios: rampa, meseta y bajada. Las mesetas son las que se
//  leen; las rampas solo sirven para llegar.
function escalones(objetivos, { rampa = '15s', sostener = '30s', bajar = '10s' } = {}) {
  const stages = [];
  for (const objetivo of objetivos) {
    stages.push({ duration: rampa, target: objetivo });
    stages.push({ duration: sostener, target: objetivo });
  }
  stages.push({ duration: bajar, target: 0 });
  return stages;
}

export const opciones = {
  //  El gateway enruta a los cuatro servicios. Se mide cada ruta por separado
  //  para que un servicio lento no quede escondido en el promedio.
  gatewayLoad: {
    scenarios: {
      enrutar: {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: escalones([5, 20, MAX_VUS_SERVICIO], { rampa: '10s', sostener: '20s' }),
        gracefulRampDown: '10s',
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.01'],
      gw_me: ['p(95)<500'],
      gw_debts: ['p(95)<500'],
      gw_payments: ['p(95)<500'],
      gw_salud: ['p(95)<500'],
      ...sinLimitador,
    },
  },

  //  Lecturas de sesión contra ms-auth: quien soy y las llaves públicas. No
  //  toca el acceso con código, que es de un solo uso y tiene su propia prueba.
  authLoad: {
    scenarios: {
      lecturas: {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: escalones([5, 20, 50, 100], { rampa: '10s', sostener: '20s' }),
        gracefulRampDown: '10s',
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.01'],
      auth_me: ['p(95)<300'],
      auth_jwks: ['p(95)<300'],
    },
  },

  //  Las dos capas que frenan la fuerza bruta. Un solo usuario y en serie: lo
  //  que se cuenta es en qué número corta, y con varios a la vez el orden
  //  dejaría de ser observable.
  authLimit: {
    scenarios: {
      limite: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '3m' },
    },
    thresholds: {
      checks: ['rate==1.0'],
    },
  },

  //  Capacidad de las lecturas de deuda, por el camino completo. Los escalones
  //  son los mismos de la medición de septiembre para poder comparar.
  debtLoad: {
    scenarios: {
      lectura: {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: escalones([5, 20, 50, 100], { rampa: '20s', sostener: '30s' }),
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
      ...sinLimitador,
    },
  },

  //  Techo de ms-debt: un ritmo impuesto sobre la ruta más usada, sin pausas.
  debtStress: {
    scenarios: {
      estres: {
        executor: 'ramping-arrival-rate',
        startRate: 50,
        timeUnit: '1s',
        preAllocatedVUs: 50,
        maxVUs: 400,
        stages: [
          { duration: '15s', target: 200 }, { duration: '30s', target: 200 },
          { duration: '15s', target: 500 }, { duration: '30s', target: 500 },
          { duration: '15s', target: 1000 }, { duration: '30s', target: 1000 },
          { duration: '15s', target: 1500 }, { duration: '30s', target: 1500 },
          { duration: '15s', target: 2000 }, { duration: '30s', target: 2000 },
        ],
      },
    },
    //  Un freno de mano, no una meta: si el p95 pasa de dos segundos ya se
    //  encontró el techo, y seguir solo recalentaría la máquina.
    thresholds: {
      http_req_failed: ['rate<0.01'],
      http_req_duration: [{ threshold: 'p(95)<2000', abortOnFail: true, delayAbortEval: '20s' }],
      ...sinLimitador,
    },
  },

  //  Abrir cobros: el camino que escribe de verdad en ms-payments.
  paymentLoad: {
    scenarios: {
      pagar: {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: [
          { duration: '15s', target: Math.max(1, Math.round(MAX_VUS / 4)) },
          { duration: '30s', target: Math.max(1, Math.round(MAX_VUS / 4)) },
          { duration: '15s', target: Math.max(1, Math.round(MAX_VUS / 2)) },
          { duration: '30s', target: Math.max(1, Math.round(MAX_VUS / 2)) },
          { duration: '15s', target: MAX_VUS },
          { duration: '30s', target: MAX_VUS },
          { duration: '10s', target: 0 },
        ],
        gracefulRampDown: '10s',
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.01'],
      ruta_abrir_cobro: ['p(95)<1500'],
      ruta_consultar_cobro: ['p(95)<500'],
      ...sinLimitador,
    },
  },

  //  Techo de ms-payments leyendo el historial. Solo lectura: no abre cobros ni
  //  confirma pagos, así que se puede repetir sin dejar basura.
  paymentStress: {
    scenarios: {
      estres: {
        executor: 'ramping-arrival-rate',
        startRate: 50,
        timeUnit: '1s',
        preAllocatedVUs: 50,
        maxVUs: 400,
        stages: [
          { duration: '15s', target: 200 }, { duration: '30s', target: 200 },
          { duration: '15s', target: 500 }, { duration: '30s', target: 500 },
          { duration: '15s', target: 1000 }, { duration: '30s', target: 1000 },
          { duration: '15s', target: 1500 }, { duration: '30s', target: 1500 },
        ],
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.01'],
      http_req_duration: [{ threshold: 'p(95)<2000', abortOnFail: true, delayAbortEval: '20s' }],
    },
  },

  //  El asistente: la salud (sin sesión) y el chat (con sesión de deudor).
  //  Con LLM_PROVIDER=local responde con reglas; con un LLM de verdad la
  //  latencia la pone el proveedor y el umbral deja de ser comparable.
  aiLoad: {
    scenarios: {
      asistente: {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: escalones([5, 10, 20], { rampa: '10s', sostener: '20s' }),
        gracefulRampDown: '10s',
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.01'],
      ai_salud: ['p(95)<500'],
      ai_chat: ['p(95)<1500'],
    },
  },

  //  El portal como servicio propio: nginx sirviendo el index, los assets con
  //  hash y las rutas de React. No pasa por el gateway.
  portalLoad: {
    scenarios: {
      estaticos: {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: escalones([10, 50, 100], { rampa: '10s', sostener: '20s' }),
        gracefulRampDown: '10s',
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.01'],
      portal_inicio: ['p(95)<300'],
      portal_assets: ['p(95)<300'],
      portal_rutas: ['p(95)<300'],
    },
  },
};
