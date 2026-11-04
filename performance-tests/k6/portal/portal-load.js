// =============================================================================
//  Cuánto aguanta el portal sirviendo archivos.
// =============================================================================
//  El portal es nginx con el build de React. Esta prueba mide lo que hace sin
//  tocar la API: la portada, los assets con hash y las rutas que React resuelve
//  devolviendo el mismo index.
//
//      GET /                 la portada
//      GET /assets/...       el JS y el CSS del build, con caché de un año
//      GET /app, /login...   rutas de React: nginx devuelve index.html
//
//  Va directo a portal:8080. No pasa por el gateway, así que el limitador de
//  peticiones no interviene.
//
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/portal/portal-load.js
// =============================================================================
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { PORTAL, opciones } from '../config/options.js';

const t = {
  inicio: new Trend('portal_inicio', true),
  assets: new Trend('portal_assets', true),
  rutas: new Trend('portal_rutas', true),
};

//  Rutas de React que nginx resuelve con try_files ... /index.html.
const RUTAS = ['/login', '/app', '/app/pagos', '/app/mis-datos', '/databridge/login'];

export const options = opciones.portalLoad;

export function setup() {
  const inicio = http.get(`${PORTAL}/`);
  if (inicio.status !== 200) fail(`la portada no responde 200: ${inicio.status}`);

  //  El build de Vite nombra los assets con un hash. Se leen del index para no
  //  escribir un nombre que cambie en cada compilación.
  const encontrados = `${inicio.body}`.match(/\/assets\/[A-Za-z0-9._-]+\.(?:js|css)/g) || [];
  const assets = Array.from(new Set(encontrados));
  if (assets.length === 0) {
    console.warn('el index no trae assets con hash: solo se medirán las rutas');
  } else {
    //  Calentamiento: deja el primer asset en la caché de nginx y del sistema.
    const calentamiento = http.get(`${PORTAL}${assets[0]}`);
    if (calentamiento.status !== 200) fail(`${assets[0]} no responde 200: ${calentamiento.status}`);
  }
  return { assets };
}

export default function (datos) {
  const inicio = http.get(`${PORTAL}/`, { tags: { ruta: 'inicio' } });
  t.inicio.add(inicio.timings.duration);
  check(inicio, {
    'la portada responde 200 con el index de React': (r) => r.status === 200 && `${r.body}`.includes('id="root"'),
  });

  for (const asset of datos.assets.slice(0, 2)) {
    const respuesta = http.get(`${PORTAL}${asset}`, { tags: { ruta: 'assets' } });
    t.assets.add(respuesta.timings.duration);
    check(respuesta, { [`${asset} responde 200`]: (r) => r.status === 200 });
  }

  const ruta = RUTAS[Math.floor(Math.random() * RUTAS.length)];
  const respuesta = http.get(`${PORTAL}${ruta}`, { tags: { ruta: 'spa' } });
  t.rutas.add(respuesta.timings.duration);
  check(respuesta, {
    [`${ruta} devuelve el index de React`]: (r) => r.status === 200 && `${r.body}`.includes('id="root"'),
  });

  //  Un navegador no pide la portada y los assets en bucle sin pausa.
  sleep(0.5);
}
