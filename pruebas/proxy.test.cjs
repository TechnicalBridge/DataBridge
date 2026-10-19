const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const frontend = path.resolve(__dirname, '../frontend');
const rutas = ['/api', '/v3/api-docs', '/swagger-ui', '/webjars'];
async function configurar(t, valores = {}) {
  const previas = {};
  for (const nombre of ['DEV_PROXY_TARGET', 'PORTAL_PORT']) {
    previas[nombre] = process.env[nombre];
    delete process.env[nombre];
    if (valores[nombre] !== undefined) process.env[nombre] = valores[nombre];
  }
  t.after(() => {
    for (const [n, v] of Object.entries(previas)) {
      if (v === undefined) delete process.env[n]; else process.env[n] = v;
    }
  });
  const modulo = await import(pathToFileURL(path.join(frontend, 'vite.config.js')).href);
  return () => modulo.default({ mode: 'test' });
}

test('Vite usa la entrada publica de Docker por omision', async t => {
  const configurarProxy = await configurar(t);
  const proxy = configurarProxy().server.proxy;
  for (const ruta of rutas) assert.equal(proxy[ruta], 'http://127.0.0.1:8080');
});

test('Vite sigue PORTAL_PORT sin exponer el gateway interno', async t => {
  const configurarProxy = await configurar(t, { PORTAL_PORT: '18080' });
  for (const destino of Object.values(configurarProxy().server.proxy)) assert.equal(destino, 'http://127.0.0.1:18080');
});

test('el destino explicito tiene prioridad sobre PORTAL_PORT', async t => {
  const configurarProxy = await configurar(t, { PORTAL_PORT: '18080', DEV_PROXY_TARGET: 'https://backend.example.com' });
  for (const destino of Object.values(configurarProxy().server.proxy)) assert.equal(destino, 'https://backend.example.com');
});

for (const destino of ['file:///tmp/backend', 'http://usuario:clave@localhost', 'http://localhost?clave=ficticia']) {
  test('Vite rechaza un destino inseguro: ' + destino.split(':')[0], async t => {
    const configurarProxy = await configurar(t, { DEV_PROXY_TARGET: destino });
    assert.throws(configurarProxy, /sin credenciales/);
  });
}

test('las cuatro rutas atraviesan Vite hacia un backend local de prueba', async t => {
  const backend = http.createServer((req, res) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ ruta: req.url })); });
  await new Promise(listo => backend.listen(0, '127.0.0.1', listo));
  t.after(() => new Promise(listo => backend.close(listo)));
  await configurar(t, { DEV_PROXY_TARGET: 'http://127.0.0.1:' + backend.address().port });
  const { createServer } = await import(pathToFileURL(path.join(frontend, 'node_modules/vite/dist/node/index.js')).href);
  const vite = await createServer({ root: frontend, configFile: path.join(frontend, 'vite.config.js'), server: { host: '127.0.0.1', port: 0 }, logLevel: 'silent' });
  t.after(() => vite.close());
  await vite.listen();
  for (const ruta of rutas) {
    const respuesta = await fetch('http://127.0.0.1:' + vite.httpServer.address().port + ruta + '/comprobar');
    assert.equal(respuesta.status, 200);
    assert.equal((await respuesta.json()).ruta, ruta + '/comprobar');
  }
});
