// =============================================================================
//  Entorno de la suite de rendimiento.
// =============================================================================
//  Todo sale de variables de entorno. Los valores por omision son los de la
//  pila de Docker Compose, que es donde estan los nombres portal, gateway,
//  ms-auth, ms-debt, ms-payments, ms-ai y mailpit.
//
//  No hay credenciales escritas aca. INTERNAL_KEY llega desde el .env de
//  Compose, y las sesiones se piden en caliente: nunca se guarda un token.
// =============================================================================

const numero = (nombre, porOmision) => {
  const valor = Number(__ENV[nombre]);
  return Number.isFinite(valor) && valor > 0 ? valor : porOmision;
};

const booleano = (nombre, porOmision) => {
  const valor = `${__ENV[nombre] ?? ''}`.trim().toLowerCase();
  if (valor === '') return porOmision;
  return valor === 'true' || valor === '1' || valor === 'si' || valor === 'sí';
};

export const PORTAL = __ENV.PORTAL_URI || 'http://portal:8080';
export const GATEWAY = __ENV.GATEWAY_URI || 'http://gateway:8082';
export const AUTH = __ENV.AUTH_URI || 'http://ms-auth:8081';
export const DEBT = __ENV.DEBT_URI || 'http://ms-debt:8083';
export const PAYMENTS = __ENV.PAYMENTS_URI || 'http://ms-payments:8084';
export const AI = __ENV.AI_URI || 'http://ms-ai:8085';
export const BUZON = __ENV.MAIL_URI || 'http://mailpit:8025';

//  Para las pruebas de API se pega directo al gateway: aisla el enrutamiento
//  del nginx del portal. Las pruebas que dicen "camino completo" usan PORTAL.
export const API = __ENV.API_BASE || GATEWAY;

export const RUT_DEUDOR = __ENV.RUT_DEUDOR || '16482337-7';
export const CORREO_EMPRESA = __ENV.CORREO_EMPRESA || 'camila.reyes@example.com';
export const RUT_EMPRESA = __ENV.RUT_EMPRESA || '76000000-0';
export const CAMPANA_ID = __ENV.CAMPANA_ID || 'DEMO-PESOS';
export const DEUDA_EXTERNA = __ENV.DEUDA_EXTERNA || 'DEMO-CONVENIO';

//  Varios deudores de la demo, para que la prueba de concurrencia no haga
//  que todos los VUs repacten exactamente la misma deuda.
export const RUTS_CONCURRENCIA = (__ENV.RUTS_CONCURRENCIA
  || '16482337-7,17893456-2,76284519-9').split(',').map((rut) => rut.trim()).filter(Boolean);

export const CLAVE_INTERNA = __ENV.INTERNAL_KEY;

export const MAX_VUS = numero('MAX_VUS', 20);
export const MAX_VUS_CARGA = numero('MAX_VUS_CARGA', 100);
export const MAX_VUS_ESTRES = numero('MAX_VUS_ESTRES', 500);
export const TASA_CARGA = numero('TASA_CARGA', 20);
export const TASA_API = numero('TASA_API', 20);
export const TASA_RECUPERACION = numero('TASA_RECUPERACION', 10);
export const ITERACIONES_RESPUESTA = numero('ITERACIONES_RESPUESTA', 10);
export const REPETICIONES_VOLUMEN = numero('REPETICIONES_VOLUMEN', 1);
export const DURACION_CARGA = __ENV.DURACION_CARGA || '2m';
export const DURACION_ENDURANCE = __ENV.DURACION_ENDURANCE || '10m';
export const UMBRAL_P95_MS = numero('UMBRAL_P95_MS', 800);

export const PERMITIR_ESCRITURAS = booleano('PERMITIR_ESCRITURAS', false);
export const INCLUIR_IA = booleano('INCLUIR_IA', false);
export const VERIFICAR_PASARELAS = booleano('VERIFICAR_PASARELAS', false);

//  Donde viven los CSV/XLSX y donde se escriben los reportes. Compose los
//  apunta a rutas absolutas del volumen; al correr local, los scripts de
//  scripts/ las calculan.
export const DATA_DIR = __ENV.DATA_DIR || '../data';
export const REPORT_DIR = __ENV.REPORT_DIR || 'reports';
export const REPORTE_NOMBRE = __ENV.REPORTE_NOMBRE || 'rendimiento';

export const CABECERA_JSON = { 'Content-Type': 'application/json' };

export function cabecerasJson(extra = {}) {
  return { ...CABECERA_JSON, ...extra };
}

export function cabeceraSesion(token, extra = {}) {
  return { Authorization: `Bearer ${token}`, ...extra };
}

/** La suite esta pensada para la pila local; no se corre contra produccion por accidente. */
export function exigirEntornoDePruebas() {
  const destinos = [PORTAL, GATEWAY, API];
  const local = destinos.every((url) => /^(https?:\/\/)?(localhost|127\.0\.0\.1|portal|gateway|ms-|\[::1\])/i.test(url));
  if (!local && !booleano('PERMITIR_ENTORNO_NO_LOCAL', false)) {
    throw new Error(
      `Esta suite escribe datos de prueba y no debe correr contra ${PORTAL}. ` +
      'Si de verdad es un entorno descartable, define PERMITIR_ENTORNO_NO_LOCAL=true.'
    );
  }
}
