// =============================================================================
//  Opciones de los doce escenarios.
// =============================================================================
//  Cada tipo de prueba tiene su propio executor y sus propios umbrales:
//
//    load          usuarios concurrentes, rampa y meseta
//    stress        ritmo creciente hasta que el p95 se dispara
//    spike         subida subita y vuelta a la normalidad
//    endurance     ritmo constante durante un periodo largo
//    volume        revision de carteras CSV y XLSX grandes
//    concurrency   muchas operaciones a la vez, incluidas repactaciones
//    scalability   escalones de usuarios con metrica por nivel
//    response-time un usuario midiendo cada operacion por separado
//    api           ritmo constante sobre el gateway, con throughput minimo
//    database      lecturas pesadas contra ms-debt (camino a MySQL)
//    recovery      sobrecarga, pausa y comprobacion de recuperacion
//    capacity      escalones de ritmo para ubicar la capacidad sostenible
//
//  Los umbrales son criterios de aceptacion: si no se cumplen, k6 termina con
//  codigo distinto de cero. Los escenarios de estres, recuperacion y capacidad
//  llevan un freno de mano para no recalentar la maquina cuando ya se encontro
//  el limite.
// =============================================================================
import {
  DURACION_CARGA,
  DURACION_ENDURANCE,
  INCLUIR_IA,
  ITERACIONES_RESPUESTA,
  MAX_VUS,
  MAX_VUS_ESTRES,
  PERMITIR_ESCRITURAS,
  REPETICIONES_VOLUMEN,
  TASA_API,
  TASA_CARGA,
  TASA_RECUPERACION,
} from './env.js';

const sinLimitador = {
  limitador_intervino: [{ threshold: 'count<1', abortOnFail: true }],
};

const sinErrores = {
  http_req_failed: ['rate<0.01'],
  suite_tasa_exito: ['rate>0.99'],
};

const escalonesVus = (objetivos, { rampa = '15s', sostener = '30s', bajar = '10s' } = {}) => {
  const stages = [];
  for (const objetivo of objetivos) {
    stages.push({ duration: rampa, target: objetivo });
    stages.push({ duration: sostener, target: objetivo });
  }
  stages.push({ duration: bajar, target: 0 });
  return stages;
};

const nivelesEscalabilidad = `${__ENV.NIVELES_ESCALABILIDAD || '10,25,50'}`
  .split(',')
  .map((valor) => Number(valor.trim()))
  .filter((valor) => Number.isFinite(valor) && valor > 0);

const escenariosEscalabilidad = {};
const umbralesEscalabilidad = {};
let inicioEscalabilidad = 0;
for (const nivel of nivelesEscalabilidad) {
  const nombre = `nivel_${nivel}`;
  escenariosEscalabilidad[nombre] = {
    executor: 'constant-vus',
    vus: nivel,
    duration: '25s',
    startTime: `${inicioEscalabilidad}s`,
    gracefulStop: '5s',
  };
  //  Hasta 25 usuarios se exige el umbral normal; los escalones altos son
  //  informativos y tienen un limite mas holgado para no fallar por ruido.
  const limite = nivel <= 25 ? 800 : nivel <= 50 ? 1200 : 2000;
  umbralesEscalabilidad[`suite_duracion{nivel:${nombre}}`] = [`p(95)<${limite}`];
  umbralesEscalabilidad[`suite_tasa_exito{nivel:${nombre}}`] = ['rate>0.99'];
  inicioEscalabilidad += 30;
}

const tasasCapacidad = `${__ENV.TASAS_CAPACIDAD || '50,100,200,400'}`
  .split(',')
  .map((valor) => Number(valor.trim()))
  .filter((valor) => Number.isFinite(valor) && valor > 0);

const escenariosCapacidad = {};
const umbralesCapacidad = {
  http_req_failed: ['rate<0.02'],
  suite_tasa_exito: ['rate>0.98'],
  ...sinLimitador,
};
let inicioCapacidad = 0;
for (const tasa of tasasCapacidad) {
  const nombre = `nivel_${tasa}`;
  escenariosCapacidad[nombre] = {
    executor: 'constant-arrival-rate',
    rate: tasa,
    timeUnit: '1s',
    duration: '20s',
    startTime: `${inicioCapacidad}s`,
    preAllocatedVUs: Math.max(20, Math.ceil(tasa / 10)),
    maxVUs: Math.max(50, tasa),
    gracefulStop: '15s',
  };
  //  Hasta 200/s se exige el SLO de latencia. Por encima, el escalon es una
  //  sonda para ubicar la rodilla: se acepta que el p95 suba, pero no que se
  //  dispare ni que aparezcan errores. La capacidad sostenida es el ultimo
  //  nivel que cumple el SLO estricto.
  const limite = tasa <= 100 ? 800 : tasa <= 200 ? 1200 : 5000;
  umbralesCapacidad[`suite_duracion{nivel:${nombre}}`] = [`p(95)<${limite}`];
  umbralesCapacidad[`suite_tasa_exito{nivel:${nombre}}`] = ['rate>0.98'];
  inicioCapacidad += 25;
}

const escenariosConcurrencia = {
  consultas: {
    executor: 'shared-iterations',
    vus: Math.max(10, MAX_VUS),
    iterations: Math.max(30, MAX_VUS * 3),
    maxDuration: '3m',
  },
};

const umbralesConcurrencia = {
  http_req_failed: ['rate<0.05'],
  suite_tasa_exito: ['rate>0.95'],
  'suite_duracion{operacion:concurrencia_lista}': ['p(95)<1500'],
  'suite_duracion{operacion:concurrencia_detalle}': ['p(95)<1500'],
  'suite_duracion{operacion:concurrencia_simular}': ['p(95)<1500'],
  ...sinLimitador,
};

if (PERMITIR_ESCRITURAS) {
  escenariosConcurrencia.repactaciones = {
    executor: 'shared-iterations',
    vus: 3,
    iterations: 3,
    maxDuration: '2m',
    exec: 'repactar',
  };
  umbralesConcurrencia['suite_duracion{operacion:concurrencia_repactar}'] = ['p(95)<3000'];
}

export const opciones = {
  //  Humo: un recorrido corto de todo lo que la suite necesita. No busca
  //  capacidad: busca decir "la pila esta arriba y las rutas responden".
  smoke: {
    scenarios: {
      humo: {
        executor: 'shared-iterations',
        vus: 1,
        iterations: 1,
        maxDuration: '3m',
      },
    },
    thresholds: {
      ...sinErrores,
      'suite_duracion{operacion:cartera_smoke_csv}': ['p(95)<15000'],
      'suite_duracion{operacion:cartera_smoke_xlsx}': ['p(95)<20000'],
      ...sinLimitador,
    },
  },

  //  1. Carga: la cantidad de usuarios que se espera en un dia normal.
  load: {
    scenarios: {
      navegacion: {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: [
          { duration: '15s', target: 5 },
          { duration: '30s', target: 5 },
          { duration: '15s', target: MAX_VUS },
          { duration: '60s', target: MAX_VUS },
          { duration: '10s', target: 0 },
        ],
        gracefulRampDown: '15s',
      },
    },
    thresholds: {
      ...sinErrores,
      'suite_duracion{operacion:load_lista}': ['p(95)<800'],
      'suite_duracion{operacion:load_detalle}': ['p(95)<800'],
      'suite_duracion{operacion:load_simular}': ['p(95)<800'],
      'suite_duracion{operacion:load_pagos}': ['p(95)<500'],
      'suite_duracion{operacion:load_me}': ['p(95)<500'],
      'suite_duracion{operacion:load_resumen}': ['p(95)<1500'],
      'suite_duracion{operacion:load_ia_salud}': ['p(95)<500'],
      ...(INCLUIR_IA
        ? { 'suite_duracion{operacion:load_ia_chat}': ['p(95)<2000'] }
        : {}),
      ...sinLimitador,
    },
  },

  //  2. Estres: ritmo creciente sobre la lectura mas usada. El freno de mano
  //     corta cuando el p95 pasa de dos segundos: ahi ya se encontro el techo.
  stress: {
    scenarios: {
      estres: {
        executor: 'ramping-arrival-rate',
        startRate: 50,
        timeUnit: '1s',
        preAllocatedVUs: 100,
        maxVUs: MAX_VUS_ESTRES,
        stages: [
          { duration: '10s', target: 200 }, { duration: '20s', target: 200 },
          { duration: '10s', target: 500 }, { duration: '20s', target: 500 },
          { duration: '10s', target: 1000 }, { duration: '20s', target: 1000 },
          { duration: '10s', target: 1500 }, { duration: '20s', target: 1500 },
        ],
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.02'],
      suite_tasa_exito: ['rate>0.98'],
      'suite_duracion{operacion:stress_lista}': [
        { threshold: 'p(95)<2000', abortOnFail: true, delayAbortEval: '20s' },
      ],
      ...sinLimitador,
    },
  },

  //  3. Pico: una subida subita de 5 a 150 usuarios y la vuelta. Se mide la
  //     fase de pico y la de despues, para distinguir "aguanto" de "se recupero".
  spike: {
    scenarios: {
      base: {
        executor: 'constant-vus',
        vus: 5,
        duration: '30s',
        startTime: '0s',
      },
      pico: {
        executor: 'ramping-vus',
        startVUs: 5,
        stages: [
          { duration: '5s', target: 150 },
          { duration: '15s', target: 150 },
          { duration: '5s', target: 5 },
        ],
        startTime: '30s',
        gracefulRampDown: '5s',
      },
      despues: {
        executor: 'constant-vus',
        vus: 5,
        duration: '20s',
        startTime: '55s',
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.02'],
      'suite_duracion{fase:pico}': ['p(95)<2500'],
      'suite_tasa_exito{fase:pico}': ['rate>0.95'],
      'suite_duracion{fase:despues}': ['p(95)<1000'],
      'suite_tasa_exito{fase:despues}': ['rate>0.99'],
      ...sinLimitador,
    },
  },

  //  4. Resistencia: ritmo constante durante un periodo largo. El objetivo es
  //     detectar fugas o degradacion, no buscar el techo.
  endurance: {
    scenarios: {
      sostenido: {
        executor: 'constant-arrival-rate',
        rate: TASA_CARGA,
        timeUnit: '1s',
        duration: DURACION_ENDURANCE,
        preAllocatedVUs: Math.max(20, TASA_CARGA),
        maxVUs: Math.max(50, TASA_CARGA * 5),
        gracefulStop: '30s',
      },
    },
    thresholds: {
      ...sinErrores,
      'suite_duracion{operacion:endurance_lista}': ['p(95)<1000'],
      'suite_duracion{operacion:endurance_detalle}': ['p(95)<1000'],
      'suite_duracion{operacion:endurance_simular}': ['p(95)<1000'],
      ...sinLimitador,
    },
  },

  //  5. Volumen: dos carteras grandes, una CSV y una XLSX, por la ruta de
  //     revision que siempre hace rollback. No deja deudas ni contactos.
  volume: {
    scenarios: {
      carteras: {
        executor: 'shared-iterations',
        vus: 1,
        iterations: REPETICIONES_VOLUMEN,
        maxDuration: '15m',
      },
    },
    thresholds: {
      ...sinErrores,
      'suite_duracion{operacion:cartera_csv}': ['p(95)<20000'],
      'suite_duracion{operacion:cartera_xlsx}': ['p(95)<25000'],
      ...sinLimitador,
    },
  },

  //  6. Concurrencia: veinte usuarios consultando a la vez y tres aceptando
  //     repactaciones en deudas distintas. Las escrituras se pueden apagar con
  //     PERMITIR_ESCRITURAS=false.
  concurrency: {
    scenarios: escenariosConcurrencia,
    thresholds: umbralesConcurrencia,
  },

  //  7. Escalabilidad: los mismos usuarios en escalones separados. Cada nivel
  //     tiene su propia etiqueta, asi se ve donde empieza a crecer la latencia.
  scalability: {
    scenarios: escenariosEscalabilidad,
    thresholds: {
      http_req_failed: ['rate<0.01'],
      ...umbralesEscalabilidad,
      ...sinLimitador,
    },
  },

  //  8. Tiempo de respuesta: un solo usuario, todas las operaciones, sin
  //     ruido de concurrencia. Los umbrales son los del README.
  responseTime: {
    scenarios: {
      medir: {
        executor: 'per-vu-iterations',
        vus: 1,
        iterations: ITERACIONES_RESPUESTA,
        maxDuration: '5m',
      },
    },
    thresholds: {
      ...sinErrores,
      'suite_duracion{operacion:rt_portal}': ['p(95)<500'],
      'suite_duracion{operacion:rt_me}': ['p(95)<300'],
      'suite_duracion{operacion:rt_lista}': ['p(95)<800'],
      'suite_duracion{operacion:rt_detalle}': ['p(95)<800'],
      'suite_duracion{operacion:rt_simular}': ['p(95)<800'],
      'suite_duracion{operacion:rt_pagos}': ['p(95)<500'],
      'suite_duracion{operacion:rt_resumen}': ['p(95)<1500'],
      'suite_duracion{operacion:rt_ia_salud}': ['p(95)<500'],
      ...(INCLUIR_IA
        ? { 'suite_duracion{operacion:rt_ia_chat}': ['p(95)<1500'] }
        : {}),
      ...sinLimitador,
    },
  },

  //  9. API: ritmo constante contra el gateway, midiendo latencia, errores y
  //     throughput. El minimo de http_reqs obliga a que la prueba realmente
  //     haya pedido el ritmo que dice.
  api: {
    scenarios: {
      ritmo: {
        executor: 'constant-arrival-rate',
        rate: TASA_API,
        timeUnit: '1s',
        duration: DURACION_CARGA,
        preAllocatedVUs: Math.max(20, TASA_API),
        maxVUs: Math.max(100, TASA_API * 5),
        gracefulStop: '20s',
      },
    },
    thresholds: {
      ...sinErrores,
      http_reqs: ['rate>30'],
      'suite_duracion{operacion:api_me}': ['p(95)<500'],
      'suite_duracion{operacion:api_lista}': ['p(95)<800'],
      'suite_duracion{operacion:api_simular}': ['p(95)<800'],
      'suite_duracion{operacion:api_pagos}': ['p(95)<500'],
      'suite_duracion{operacion:api_ia_salud}': ['p(95)<500'],
      'suite_duracion{operacion:api_resumen}': ['p(95)<1500'],
      ...(INCLUIR_IA
        ? { 'suite_duracion{operacion:api_ia_chat}': ['p(95)<2000'] }
        : {}),
      ...sinLimitador,
    },
  },

  //  10. Base de datos: no hay un endpoint de MySQL, y no se inventa. Se
  //      miden las lecturas de ms-debt que hacen joins y agregaciones; si la
  //      base se degrada, estas rutas lo muestran antes que las demas.
  database: {
    scenarios: {
      lecturas: {
        executor: 'constant-vus',
        vus: 5,
        duration: DURACION_CARGA,
        gracefulStop: '15s',
      },
    },
    thresholds: {
      ...sinErrores,
      'suite_duracion{operacion:db_lista}': ['p(95)<800'],
      'suite_duracion{operacion:db_detalle}': ['p(95)<800'],
      'suite_duracion{operacion:db_historial}': ['p(95)<800'],
      'suite_duracion{operacion:db_vencimientos}': ['p(95)<800'],
      'suite_duracion{operacion:db_resumen}': ['p(95)<1500'],
      ...sinLimitador,
    },
  },

  //  11. Recuperacion: 30 segundos de sobrecarga, una pausa y 30 segundos de
  //      carga normal. El umbral de la fase de recuperacion es el que importa:
  //      el sistema tiene que volver, no solo haber aguantado.
  recovery: {
    scenarios: {
      sobrecarga: {
        executor: 'constant-arrival-rate',
        rate: 300,
        timeUnit: '1s',
        duration: '30s',
        preAllocatedVUs: 100,
        maxVUs: 400,
        gracefulStop: '10s',
        startTime: '0s',
      },
      recuperacion: {
        executor: 'constant-vus',
        vus: Math.max(5, TASA_RECUPERACION),
        duration: '30s',
        startTime: '45s',
        gracefulStop: '10s',
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.02'],
      'suite_duracion{fase:sobrecarga}': ['p(95)<5000'],
      'suite_duracion{fase:recuperacion}': ['p(95)<1000'],
      'suite_tasa_exito{fase:recuperacion}': ['rate>0.99'],
      ...sinLimitador,
    },
  },

  //  12. Capacidad: escalones de ritmo fijos. El resumen muestra p95, errores
  //      e iteraciones perdidas por nivel; el ultimo nivel que cumple los
  //      umbrales es la capacidad sostenible observada.
  capacity: {
    scenarios: escenariosCapacidad,
    thresholds: umbralesCapacidad,
  },
};

export const NOMBRES = {
  smoke: 'smoke',
  load: 'load',
  stress: 'stress',
  spike: 'spike',
  endurance: 'endurance',
  volume: 'volume',
  concurrency: 'concurrency',
  scalability: 'scalability',
  responseTime: 'response-time',
  api: 'api-performance',
  database: 'database-performance',
  recovery: 'recovery',
  capacity: 'capacity',
};

export const ESCENARIOS = Object.keys(NOMBRES).filter((clave) => clave !== 'smoke');
