// =============================================================================
//  Sesiones de la suite.
// =============================================================================
//  No se guardan credenciales ni tokens en archivos. Cada setup pide lo que
//  necesita:
//
//    - deudor: un codigo por la red interna de ms-auth y se canjea en el portal
//    - empresa: un enlace al correo, se lee del buzon de Mailpit y se canjea
//
//  El enlace de empresa es el mismo camino que recorre una persona. Se lee de
//  Mailpit a proposito: no hay un atajo de prueba que se salte el correo.
// =============================================================================
import http from 'k6/http';
import { fail, sleep } from 'k6';
import {
  AUTH,
  BUZON,
  CLAVE_INTERNA,
  CORREO_EMPRESA,
  PORTAL,
  RUT_DEUDOR,
  cabecerasJson,
} from '../config/env.js';

function exigirClaveInterna() {
  if (!CLAVE_INTERNA) {
    fail('Falta INTERNAL_KEY. Corre la suite con docker compose, que la toma del .env, '
      + 'o define INTERNAL_KEY en el entorno.');
  }
  return CLAVE_INTERNA;
}

function jsonSeguro(respuesta, ruta) {
  try {
    return respuesta.json(ruta);
  } catch (error) {
    return undefined;
  }
}

/**
 * Una sesion de deudor: se emite un codigo de un solo uso y se canjea.
 *
 * El codigo se pide con un canal que no sea correo: la prueba se queda con el
 * codigo que devuelve la red interna y no llena el buzon de Mailpit con un
 * mensaje por escenario. El canje si es el camino real del portal.
 */
export function sesionDeudor(rut = RUT_DEUDOR) {
  const emision = http.post(`${AUTH}/internal/codigos`, JSON.stringify({
    rut,
    canales: ['interno'],
    paraQue: 'medicion',
  }), {
    headers: {
      ...cabecerasJson(),
      'X-Internal-Key': exigirClaveInterna(),
    },
  });
  if (emision.status !== 200) {
    fail(`no se pudo emitir el codigo para ${rut}: ${emision.status} ${emision.body}`);
  }

  const entrada = http.post(`${PORTAL}/api/auth/acceso`,
    JSON.stringify({ rut, codigo: emision.json('codigo') }),
    { headers: cabecerasJson() });
  if (entrada.status !== 200) {
    fail(`el deudor ${rut} no pudo entrar: ${entrada.status} ${entrada.body}`);
  }
  return entrada.json('token');
}

/**
 * Una sesion de empresa. El enlace llega al buzon de prueba y se canjea en el
 * portal. Devuelve null si no llega, para que un escenario de solo lectura
 * pueda seguir sin las rutas de empresa.
 */
export function sesionEmpresa(correo = CORREO_EMPRESA) {
  const antes = Date.now();
  const pedido = http.post(`${PORTAL}/api/auth/enlace`,
    JSON.stringify({ correo }), { headers: cabecerasJson() });
  if (pedido.status !== 202) {
    console.warn(`el enlace de empresa respondio ${pedido.status}: ${pedido.body}`);
    return null;
  }

  for (let intento = 0; intento < 20; intento++) {
    sleep(0.5);
    const bandeja = http.get(`${BUZON}/api/v1/messages?limit=5`);
    if (bandeja.status !== 200) continue;
    for (const mensaje of bandeja.json('messages') || []) {
      if (new Date(mensaje.Created).getTime() < antes - 5000) continue;
      const cuerpo = http.get(`${BUZON}/api/v1/message/${mensaje.ID}`);
      const texto = `${jsonSeguro(cuerpo, 'Text') || ''} ${jsonSeguro(cuerpo, 'HTML') || ''} ${cuerpo.body}`;
      const ficha = texto.match(/magic\?token=([0-9a-f-]{36})/);
      if (!ficha) continue;
      const canje = http.post(`${PORTAL}/api/auth/verify`,
        JSON.stringify({ token: ficha[1] }), { headers: cabecerasJson() });
      if (canje.status === 200) return canje.json('token');
    }
  }
  console.warn(`no llego el enlace de ${correo} al buzon de prueba`);
  return null;
}

export function sesionEmpresaObligatoria(correo = CORREO_EMPRESA) {
  const token = sesionEmpresa(correo);
  if (!token) {
    fail(`no se pudo abrir la sesion de empresa de ${correo}. Revisa que DEMO_DATOS=true y Mailpit este arriba.`);
  }
  return token;
}
