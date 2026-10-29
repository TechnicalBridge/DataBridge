// =============================================================================
//  Las reglas de .gitleaks.toml y el verificador (#232):  node --test pruebas/secretos.test.cjs
// =============================================================================
//  Las cuatro primeras pruebas no necesitan gitleaks. Las demas lo corren de
//  verdad, sobre un archivo o un repositorio de prueba, y se saltan si no esta
//  (GITLEAKS apunta al binario; la CI lo instala). Cada clave de prueba se
//  arma al azar en el momento: ninguna queda escrita en este archivo.
// =============================================================================
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const { verificar } = require('../scripts/verificar-secretos.cjs');

test('solo acepta el indice o un SHA completo, no opciones de git arbitrarias', () => {
  for (const args of [[], ['--base', '--all'], ['--base', 'main'], ['--staged', '--base']]) {
    assert.throws(() => verificar(args));
  }
});

test('el resumen dice la regla, el archivo y la linea, pero nunca el valor ni el autor', () => {
  const r = verificar(['--staged'], (bin, args) => {
    assert.ok(args.includes('--redact=100'));
    assert.ok(args.includes('--ignore-gitleaks-allow'));
    assert.ok(args.includes('--staged'));
    const reporte = args[args.indexOf('--report-path') + 1];
    fs.writeFileSync(reporte, JSON.stringify([{ RuleID: 'regla-ficticia', File: 'archivo.txt', StartLine: 4,
      Secret: 'ficticio-no-mostrar', Match: 'ficticio-no-mostrar', Email: 'ficticio@example.com' }]));
    return { status: 1 };
  });
  assert.equal(r.correcto, false);
  assert.deepEqual(r.ubicaciones, [{ regla: 'regla-ficticia', archivo: 'archivo.txt', linea: 4 }]);
  assert.ok(!JSON.stringify(r).includes('ficticio-no-mostrar'));
  assert.ok(!JSON.stringify(r).includes('ficticio@example.com'));
});

test('con --base, revisa cada merge contra la linea propia de la rama', () => {
  const sha = 'a'.repeat(40);
  verificar(['--base', sha], (bin, args) => {
    assert.ok(args.includes(`--log-opts=-m --first-parent ${sha}..HEAD`));
    fs.writeFileSync(args[args.indexOf('--report-path') + 1], '[]');
    return { status: 0 };
  });
});

test('si gitleaks no corre, la revision falla en vez de dar todo por bueno', () => {
  assert.throws(() => verificar(['--staged'], () => ({ status: 2 })), /No se pudo ejecutar/);
});

const azar = (n) => crypto.randomBytes(n).toString('base64url');
const hex = (n) => crypto.randomBytes(n).toString('hex');
const pem = () => crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' })
  .privateKey.export({ type: 'pkcs8', format: 'pem' });

//  [lo que se prueba, el texto del archivo, la regla que tiene que saltar o null]
const casos = [
  ['un access token de Mercado Pago',
    () => 'MERCADOPAGO_ACCESS_TOKEN=' + ['APP_USR', '1234567890123456', '123456', hex(16), '123456789'].join('-'),
    'databridge-mercadopago-token'],
  ['una API key de Render', () => 'RENDER_API_KEY=rnd_' + hex(20), 'databridge-render-key'],
  ['CIFRADO_LLAVE con su valor', () => 'CIFRADO_LLAVE=' + azar(48), 'databridge-variable-con-valor'],
  ['la llave anterior con su valor, mientras se cambia', () => 'CIFRADO_LLAVE_ANTERIOR=' + azar(48),
    'databridge-variable-con-valor'],
  ['la clave de una de las tres bases', () => 'DB_DEBT_PASSWORD=' + azar(48), 'databridge-variable-con-valor'],
  ['una clave de root de una base', () => 'DB_AUTH_ROOT_PASSWORD=' + azar(48), 'databridge-variable-con-valor'],
  ['una clave de un .env viejo', () => 'MYSQL_PASSWORD=' + azar(48), 'databridge-variable-con-valor'],
  ['una clave en YAML', () => 'services:\n  ms-debt:\n    environment:\n      INTERNAL_KEY: ' + azar(32) + '\n',
    'databridge-variable-con-valor'],
  ['una huella que no es de las publicas', () => 'CIFRADO_LLAVE=' + hex(32), 'databridge-variable-con-valor'],
  ['un valor por omision en las propiedades', () => 'app.cifrado.llave=${CIFRADO_LLAVE:' + hex(32) + '}',
    'databridge-secreto-por-omision'],
  ['un valor por omision en el compose', () => 'BREVO_API_KEY: ${BREVO_API_KEY:-' + azar(32) + '}',
    'databridge-secreto-por-omision'],
  ['un valor por omision para la llave anterior',
    () => 'CIFRADO_LLAVE_ANTERIOR: ${CIFRADO_LLAVE_ANTERIOR:-' + azar(32) + '}', 'databridge-secreto-por-omision'],
  ['una llave privada (como la de ES256)', pem, 'private-key'],
  ['la llave de integracion que publica Transbank',
    () => 'TRANSBANK_API_KEY=579B532A7440BB0C9079DED94D31EA1615BACEB56610332264630D42D0A36B1C', null],
  ['las huellas de preparar-env.ps1',
    () => "$secretos = [ordered]@{\n    RABBIT_PASSWORD     = '84983c60f7daadc1cb8698621f802c0d9f9a3c3c295c810748fb048115c186ec'\n"
      + "    CIFRADO_LLAVE       = '774db0f5ff463139c885933a38068fec5a0648f9fc9f2605d6c786eeac406318'\n}\n", null],
  ['las variables vacias de .env.example',
    () => 'MYSQL_USER=tbridge\nDB_AUTH_ROOT_PASSWORD=\nDB_AUTH_PASSWORD=\nCIFRADO_LLAVE=\nCIFRADO_LLAVE_ANTERIOR=\nBCENTRAL_PASS=\n', null],
  ['la Public Key de Mercado Pago', () => 'MERCADOPAGO_PUBLIC_KEY=APP_USR-' + crypto.randomUUID(), null],
  ['una referencia obligatoria del compose',
    () => '      MYSQL_PASSWORD: ${DB_DEBT_PASSWORD:?Falta DB_DEBT_PASSWORD en el .env (crealo con preparar-env.ps1)}\n'
      + '      CIFRADO_LLAVE: ${CIFRADO_LLAVE:?Falta CIFRADO_LLAVE}\n'
      + '      CIFRADO_LLAVE_ANTERIOR: ${CIFRADO_LLAVE_ANTERIOR:-}\n', null],
  ['la llave anterior vacia por omision, en las propiedades',
    () => 'app.cifrado.llave-anterior=${CIFRADO_LLAVE_ANTERIOR:}\n', null],
];

const binario = process.env.GITLEAKS;

//  El caso que se le escapaba a git log -p: la clave entra solo al resolver un
//  conflicto, en el commit de merge, y no esta en ninguno de los dos lados.
test('detecta una clave escrita al resolver un conflicto de merge', { skip: !binario && 'sin GITLEAKS' }, (t) => {
  const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'tb-merge-'));
  t.after(() => {
    assert.ok(path.basename(repo).startsWith('tb-merge-'));
    fs.rmSync(repo, { recursive: true, force: true });
  });
  const git = (...a) => {
    const r = spawnSync('git', ['-C', repo, '-c', 'user.name=prueba', '-c', 'user.email=prueba@example.com', ...a],
      { encoding: 'utf8', windowsHide: true });
    return r.stdout.trim();
  };
  const escribir = (texto) => fs.writeFileSync(path.join(repo, 'a.txt'), texto);
  git('init', '-q', '-b', 'main');
  escribir('valor=uno\n'); git('add', 'a.txt'); git('commit', '-qm', 'base');
  const base = git('rev-parse', 'HEAD');
  git('checkout', '-qb', 'rama'); escribir('valor=dos\n'); git('commit', '-qam', 'rama');
  git('checkout', '-q', 'main'); escribir('valor=tres\n'); git('commit', '-qam', 'main');
  git('checkout', '-q', 'rama'); git('merge', '-q', 'main');
  escribir('valor=cuatro\nRENDER_API_KEY=rnd_' + hex(20) + '\n');
  git('add', 'a.txt'); git('commit', '-qm', 'merge con el conflicto resuelto');
  fs.copyFileSync(path.resolve(__dirname, '../.gitleaks.toml'), path.join(repo, '.gitleaks.toml'));
  const r = verificar(['--base', base], spawnSync, repo);
  assert.equal(r.correcto, false);
  assert.deepEqual(r.ubicaciones.map((u) => u.regla), ['databridge-render-key']);
});

for (const [nombre, texto, regla] of casos) {
  test((regla ? 'detecta ' : 'deja pasar ') + nombre, { skip: !binario && 'sin GITLEAKS' }, (t) => {
    const raiz = fs.mkdtempSync(path.join(os.tmpdir(), 'tb-deteccion-'));
    t.after(() => {
      assert.equal(path.dirname(raiz), os.tmpdir());
      assert.ok(path.basename(raiz).startsWith('tb-deteccion-'));
      fs.rmSync(raiz, { recursive: true, force: true });
    });
    const archivos = path.join(raiz, 'archivos');
    fs.mkdirSync(archivos);
    fs.writeFileSync(path.join(archivos, 'ejemplo.txt'), texto());
    const reporte = path.join(raiz, 'resultado.json');
    const r = spawnSync(binario, ['dir', archivos, '--config', path.resolve(__dirname, '../.gitleaks.toml'),
      '--report-path', reporte, '--report-format', 'json', '--redact=100', '--no-banner', '--no-color',
      '--log-level', 'error'], { windowsHide: true, encoding: 'utf8' });
    assert.equal(r.status, regla ? 1 : 0, 'gitleaks no lo clasifico como se esperaba (su salida no se muestra)');
    const reglas = JSON.parse(fs.readFileSync(reporte, 'utf8')).map((h) => h.RuleID);
    if (regla) assert.ok(reglas.includes(regla), `tenia que saltar ${regla} y salto ${reglas.join(', ')}`);
    else assert.deepEqual(reglas, []);
  });
}
