const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const { verificar } = require('../scripts/verificar-secretos.cjs');

test('solo acepta el indice o un SHA completo, no opciones de git arbitrarias', () => {
  for (const args of [[], ['--base', '--all'], ['--base', 'main'], ['--staged', '--base']]) assert.throws(() => verificar(args));
});

test('el resumen muestra ubicacion pero nunca Secret, Match ni autorias', () => {
  const r = verificar(['--staged'], (bin, args) => {
    assert.ok(args.includes('--redact=100'));
    assert.ok(args.includes('--ignore-gitleaks-allow'));
    assert.ok(args.includes('--staged'));
    const reporte = args[args.indexOf('--report-path') + 1];
    fs.writeFileSync(reporte, JSON.stringify([{ RuleID: 'regla-ficticia', File: 'archivo.txt', StartLine: 4, Secret: 'ficticio-no-mostrar', Match: 'ficticio-no-mostrar', Email: 'ficticio@example.com' }]));
    return { status: 1 };
  });
  assert.equal(r.correcto, false);
  assert.deepEqual(r.ubicaciones, [{ regla: 'regla-ficticia', archivo: 'archivo.txt', linea: 4 }]);
  assert.ok(!JSON.stringify(r).includes('ficticio-no-mostrar'));
});

test('un detector ausente o fallido bloquea la comprobacion', () => {
  assert.throws(() => verificar(['--staged'], () => ({ status: 2 })), /No se pudo ejecutar/);
});

const binario = process.env.GITLEAKS;
for (const [nombre, texto, regla] of [
  ['token privado de Mercado Pago', () => 'MERCADOPAGO_ACCESS_TOKEN=' + ['APP_USR', '1234567890123456', '123456', crypto.randomBytes(16).toString('hex'), '123456789'].join('-'), 'databridge-mercadopago-token'],
  ['API key de Render', () => 'RENDER_API_KEY=' + 'rnd_' + crypto.randomBytes(20).toString('hex'), 'databridge-render-key'],
  ['llave literal de cifrado', () => 'CIFRADO_LLAVE=' + crypto.randomBytes(48).toString('base64url'), 'databridge-entorno-literal'],
  ['hash arbitrario no permitido', () => 'CIFRADO_LLAVE=' + crypto.randomBytes(32).toString('hex'), 'databridge-entorno-literal'],
  ['valor por omision en propiedades', () => 'app.cifrado.llave=${CIFRADO_LLAVE:' + crypto.randomBytes(32).toString('hex') + '}', 'databridge-secreto-fallback'],
  ['llave oficial de integracion', () => 'TRANSBANK_API_KEY=579B532A7440BB0C9079DED94D31EA1615BACEB56610332264630D42D0A36B1C', null],
  ['variables vacias en lineas consecutivas', () => 'MYSQL_ROOT_PASSWORD=\nMYSQL_PASSWORD=\nMYSQL_USER=tbridge\nBCENTRAL_PASS=\nBCENTRAL_USER=\n', null],
  ['Public Key de Mercado Pago', () => 'MERCADOPAGO_PUBLIC_KEY=APP_USR-' + crypto.randomUUID(), null],
  ['referencia obligatoria sin valor por omision', () => 'CIFRADO_LLAVE=${CIFRADO_LLAVE:?Falta CIFRADO_LLAVE}', null],
]) {
  test('Gitleaks clasifica ' + nombre, { skip: !binario }, t => {
    const raiz = fs.mkdtempSync(path.join(os.tmpdir(), 'tb-deteccion-'));
    const archivos = path.join(raiz, 'archivos');
    fs.mkdirSync(archivos);
    fs.writeFileSync(path.join(archivos, 'ejemplo.txt'), texto());
    const reporte = path.join(raiz, 'resultado.json');
    t.after(() => {
      assert.equal(path.dirname(raiz), os.tmpdir());
      assert.ok(path.basename(raiz).startsWith('tb-deteccion-'));
      fs.rmSync(raiz, { recursive: true, force: true });
    });
    const r = spawnSync(binario, ['dir', archivos, '--config', path.resolve(__dirname, '../.gitleaks.toml'), '--report-path', reporte, '--report-format', 'json', '--redact=100', '--no-banner', '--no-color', '--log-level', 'error'], { windowsHide: true, encoding: 'utf8' });
    assert.equal(r.status, regla ? 1 : 0, 'Clasificacion esperada sin publicar la salida del detector');
    const reglas = JSON.parse(fs.readFileSync(reporte, 'utf8')).map(h => h.RuleID);
    if (regla) assert.ok(reglas.includes(regla), 'Debe detectar la regla esperada');
    else assert.equal(reglas.length, 0);
  });
}
