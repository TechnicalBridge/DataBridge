// =============================================================================
//  Cuánto aguanta ms-auth leyendo la sesión.
// =============================================================================
//  Dos lecturas que el portal y los demás servicios hacen todo el tiempo:
//
//      GET /api/me                  quién soy, con el JWT de la sesión
//      GET /.well-known/jwks.json   las llaves públicas con que se verifica
//
//  Va directo a ms-auth:8081, sin gateway, para medir el servicio. El acceso
//  con código no se repite aquí: el código sirve una sola vez y tiene su
//  propia prueba en auth-limit.js.
//
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/ms-auth/auth-load.js
// =============================================================================
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { AUTH, RUT_DEUDOR, opciones, sesionDeudor } from '../config/options.js';

const t = {
  me: new Trend('auth_me', true),
  jwks: new Trend('auth_jwks', true),
};

export const options = opciones.authLoad;

export function setup() {
  const token = sesionDeudor(RUT_DEUDOR, 'auth-load@prueba.local', 'Prueba de ms-auth');
  const sesion = { headers: { Authorization: `Bearer ${token}` } };

  //  Calentamiento: deja la verificación del JWT y el JWKS fuera de la medición.
  const me = http.get(`${AUTH}/api/me`, sesion);
  if (me.status !== 200) fail(`GET /api/me no responde 200: ${me.status} ${me.body}`);
  const jwks = http.get(`${AUTH}/.well-known/jwks.json`);
  if (jwks.status !== 200) fail(`GET /.well-known/jwks.json no responde 200: ${jwks.status}`);
  return { token };
}

export default function (datos) {
  const me = http.get(`${AUTH}/api/me`, {
    headers: { Authorization: `Bearer ${datos.token}` }, tags: { ruta: 'me' },
  });
  t.me.add(me.timings.duration);
  check(me, { 'GET /api/me responde 200': (r) => r.status === 200 });

  const jwks = http.get(`${AUTH}/.well-known/jwks.json`, { tags: { ruta: 'jwks' } });
  t.jwks.add(jwks.timings.duration);
  check(jwks, {
    'GET /.well-known/jwks.json responde 200 con llaves': (r) => {
      if (r.status !== 200) return false;
      const llaves = r.json('keys');
      return Array.isArray(llaves) && llaves.length > 0;
    },
  });

  sleep(0.5);
}
