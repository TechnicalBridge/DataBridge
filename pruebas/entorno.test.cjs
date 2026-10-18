const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const repo = path.resolve(__dirname, '..');
const internos = ['MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'RABBIT_PASSWORD', 'JWT_SECRET', 'INTERNAL_KEY', 'CIFRADO_LLAVE', 'WEBHOOK_SECRET'];
const propios = Object.fromEntries(internos.map((nombre, i) => [nombre, `ficticio_${i}_` + 'x'.repeat(48)]));
const plantilla = fs.readFileSync(path.join(repo, '.env.example'), 'utf8');
const preparador = process.env.PREPARADOR_ANTERIOR || path.join(repo, 'preparar-env.ps1');

function entorno(texto) {
  return Object.fromEntries(texto.split(/\r?\n/).filter(l => /^[A-Za-z_][A-Za-z0-9_]*=/.test(l)).map(l => {
    const i = l.indexOf('=');
    return [l.slice(0, i), l.slice(i + 1)];
  }));
}

function escenario(t, opciones = {}) {
  const raiz = fs.mkdtempSync(path.join(os.tmpdir(), 'tbridge-entorno-'));
  const carpeta = path.join(raiz, 'TB_web');
  fs.mkdirSync(carpeta);
  fs.copyFileSync(preparador, path.join(carpeta, 'preparar-env.ps1'));
  fs.copyFileSync(path.join(__dirname, 'docker-simulado.ps1'), path.join(carpeta, 'ejecutar.ps1'));
  fs.writeFileSync(path.join(carpeta, '.env.example'), plantilla);
  const valores = { ...propios, ...opciones.valores };
  let texto = plantilla;
  for (const [nombre, valor] of Object.entries(valores)) {
    const patron = new RegExp(`^${nombre}=.*$`, 'm');
    texto = patron.test(texto) ? texto.replace(patron, () => `${nombre}=${valor}`) : texto + `\n${nombre}=${valor}\n`;
  }
  if (opciones.transformar) texto = opciones.transformar(texto);
  const archivo = path.join(carpeta, '.env');
  if (opciones.existe !== false) fs.writeFileSync(archivo, texto);
  const docker = {
    contenedor: true, iniciado: true, proyecto: 'tb_web', volumen: true,
    root: propios.MYSQL_ROOT_PASSWORD, aplicacion: propios.MYSQL_PASSWORD,
    configRoot: propios.MYSQL_ROOT_PASSWORD, configAplicacion: propios.MYSQL_PASSWORD,
    jwt: propios.JWT_SECRET, interna: propios.INTERNAL_KEY, cifrado: propios.CIFRADO_LLAVE, webhook: propios.WEBHOOK_SECRET, ...opciones.docker,
  };
  fs.writeFileSync(path.join(carpeta, 'docker.json'), JSON.stringify(docker));
  const envHijo = { ...process.env };
  for (const nombre of [...internos, 'MYSQL_USER', 'DB_PASSWORD', 'MYSQL_PWD']) delete envHijo[nombre];
  const ejecutar = (...args) => {
    const r = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(carpeta, 'ejecutar.ps1'), path.join(carpeta, 'preparar-env.ps1'), ...args], {
      env: { ...envHijo, ...opciones.ambiente }, encoding: 'utf8', timeout: 20000,
    });
    assert.equal(r.error, undefined, 'PowerShell debe terminar dentro del limite');
    const salida = (r.stdout || '') + (r.stderr || '');
    for (const secreto of Object.values(propios)) assert.ok(!salida.includes(secreto), 'No se imprimen secretos');
    assert.ok(!salida.includes('Error simulado:'), 'No se imprime el stderr de MySQL');
    const llamadas = fs.existsSync(path.join(carpeta, 'llamadas.jsonl'))
      ? fs.readFileSync(path.join(carpeta, 'llamadas.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse) : [];
    assert.ok(llamadas.every(l => ['info', 'inspect', 'volume', 'ps', 'exec'].includes(l.argumentos[0])), 'No se levantan ni borran contenedores');
    return { codigo: r.status, salida, llamadas };
  };
  t.after(() => {
    assert.equal(path.dirname(raiz), os.tmpdir());
    assert.ok(path.basename(raiz).startsWith('tbridge-entorno-'));
    fs.rmSync(raiz, { recursive: true, force: true });
  });
  return {
    raiz, carpeta, archivo, original: texto, ejecutar,
    leer: () => fs.readFileSync(archivo, 'utf8'),
    perfil: texto => { const p = path.join(raiz, '.env.proveedores'); fs.writeFileSync(p, texto); return p; },
    copias: () => fs.existsSync(path.join(raiz, 'respaldos')) ? fs.readdirSync(path.join(raiz, 'respaldos')).map(n => path.join(raiz, 'respaldos', n)) : [],
  };
}

test('inicializa siete secretos propios sin usar proveedores ni imprimir valores', t => {
  const e = escenario(t, { existe: false, docker: { contenedor: false, volumen: false } });
  const r = e.ejecutar();
  assert.equal(r.codigo, 0, r.salida);
  const valores = entorno(e.leer());
  for (const nombre of internos) assert.match(valores[nombre], /^[A-Za-z0-9_-]{64}$/);
  assert.equal(valores.BREVO_API_KEY, '');
  assert.equal(valores.BCENTRAL_USER, '');
  assert.equal(valores.BCENTRAL_PASS, '');
  assert.equal(new Set(internos.map(n => valores[n])).size, 7);
});

test('cada instalacion genera claves diferentes', t => {
  const opciones = { existe: false, docker: { contenedor: false, volumen: false } };
  const a = escenario(t, opciones);
  const b = escenario(t, opciones);
  assert.equal(a.ejecutar().codigo, 0);
  assert.equal(b.ejecutar().codigo, 0);
  for (const nombre of internos) assert.notEqual(entorno(a.leer())[nombre], entorno(b.leer())[nombre]);
});

test('segunda preparacion conserva exactamente el archivo', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar().codigo, 0);
  assert.equal(e.leer(), e.original);
  assert.equal(e.copias().length, 0);
});

test('no regenera una clave faltante con base existente', t => {
  const e = escenario(t, { valores: { MYSQL_PASSWORD: '' } });
  assert.equal(e.ejecutar().codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('volumen sin contenedor impide inventar otro entorno', t => {
  const e = escenario(t, { existe: false, docker: { contenedor: false, volumen: true } });
  assert.equal(e.ejecutar().codigo, 1);
  assert.equal(fs.existsSync(e.archivo), false);
});

test('Docker apagado no crea ni modifica archivos', t => {
  const e = escenario(t, { existe: false, docker: { sinMotor: true } });
  assert.equal(e.ejecutar().codigo, 1);
  assert.equal(fs.existsSync(e.archivo), false);
});

for (const [nombre, docker] of [['root rechazado', { root: 'otra-clave-ficticia' }], ['aplicacion rechazada', { aplicacion: 'otra-clave-ficticia' }], ['base detenida', { iniciado: false }], ['contenedor ajeno', { proyecto: 'otro' }]]) {
  test(nombre + ' termina con error sin modificar el entorno', t => {
    const e = escenario(t, { docker });
    assert.equal(e.ejecutar().codigo, 1);
    assert.equal(e.leer(), e.original);
    assert.equal(e.copias().length, 0);
  });
}

test('verificacion autentica contra MySQL y no guarda archivos ni ejecuta ALTER', t => {
  const e = escenario(t);
  const r = e.ejecutar('-Verificar');
  assert.equal(r.codigo, 0);
  assert.equal(e.leer(), e.original);
  assert.equal(e.copias().length, 0);
  const sql = r.llamadas.filter(l => l.argumentos[0] === 'exec');
  assert.equal(sql.length, 2);
  assert.ok(sql.every(l => !/ALTER|INSERT|UPDATE|DELETE/i.test(l.consulta)));
  assert.ok(sql.some(l => /USE tb_auth; USE tb_debt; USE tb_payments/.test(l.consulta)));
});

test('verificar sin archivo no lo crea', t => {
  const e = escenario(t, { existe: false });
  assert.equal(e.ejecutar('-Verificar').codigo, 1);
  assert.equal(fs.existsSync(e.archivo), false);
});

test('verificar sin servidor no declara acceso probado', t => {
  const e = escenario(t, { docker: { contenedor: false, volumen: false } });
  assert.equal(e.ejecutar('-Verificar').codigo, 1);
});

test('verificar no admite opciones de escritura', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar('-Verificar', '-Renovar').codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('detecta servicios creados con otra clave sin cambiar la base', t => {
  const e = escenario(t, { docker: { servicio: true, claveServicio: 'otra-clave-ficticia' } });
  const r = e.ejecutar('-Verificar');
  assert.equal(r.codigo, 1);
  assert.match(r.salida, /DB_PASSWORD/);
  assert.equal(e.leer(), e.original);
});

test('servicio alineado permite la verificacion', t => {
  const e = escenario(t, { docker: { servicio: true, claveServicio: propios.MYSQL_PASSWORD } });
  const r = e.ejecutar('-Verificar');
  assert.equal(r.codigo, 0, r.salida);
});

test('actualiza opciones nuevas conservando secretos y respaldo byte a byte', t => {
  const e = escenario(t, { transformar: texto => texto.replace(/^XAI_MODEL=.*\r?\n?/m, '') });
  assert.equal(e.ejecutar().codigo, 0);
  assert.equal(entorno(e.leer()).XAI_MODEL, entorno(plantilla).XAI_MODEL);
  for (const nombre of internos) assert.equal(entorno(e.leer())[nombre], propios[nombre]);
  assert.equal(e.copias().length, 1);
  assert.equal(fs.readFileSync(path.join(e.copias()[0], '.env'), 'utf8'), e.original);
});

test('perfil explicito incorpora proveedor y conserva los secretos internos', t => {
  const e = escenario(t);
  const perfil = e.perfil('BREVO_API_KEY=proveedor-ficticio\nMAIL_FROM=prueba@example.com\nCORREO_DESVIAR_A=prueba@example.com\n');
  const r = e.ejecutar('-Proveedores', perfil);
  assert.equal(r.codigo, 0, r.salida);
  assert.equal(entorno(e.leer()).BREVO_API_KEY, 'proveedor-ficticio');
  assert.ok(!r.salida.includes('proveedor-ficticio'));
  for (const nombre of internos) assert.equal(entorno(e.leer())[nombre], propios[nombre]);
});

test('perfil no puede importar secretos de otro PC', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar('-Proveedores', e.perfil('MYSQL_PASSWORD=otra-clave-ficticia\n')).codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('proveedor propio no se pisa sin opcion expresa', t => {
  const e = escenario(t, { valores: { KHIPU_LLAVE: 'valor-propio-ficticio' } });
  assert.equal(e.ejecutar('-Proveedores', e.perfil('KHIPU_LLAVE=nueva-ficticia\n')).codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('reemplazo expreso de proveedor guarda respaldo', t => {
  const e = escenario(t, { valores: { KHIPU_LLAVE: 'valor-propio-ficticio' } });
  assert.equal(e.ejecutar('-Proveedores', e.perfil('KHIPU_LLAVE=nueva-ficticia\n'), '-ReemplazarProveedores').codigo, 0);
  assert.equal(entorno(e.leer()).KHIPU_LLAVE, 'nueva-ficticia');
  assert.equal(e.copias().length, 1);
});

test('correo real con datos de demo exige modo de prueba', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar('-Proveedores', e.perfil('BREVO_API_KEY=proveedor-ficticio\n')).codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('variables duplicadas no se resuelven arbitrariamente', t => {
  const e = escenario(t, { transformar: texto => texto + '\nMYSQL_PASSWORD=duplicada-ficticia\n' });
  assert.equal(e.ejecutar().codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('la terminal no puede sobrescribir silenciosamente una clave interna', t => {
  const e = escenario(t, { ambiente: { MYSQL_PASSWORD: 'sobrescrita-ficticia' } });
  assert.equal(e.ejecutar().codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('rechazo de ALTER USER no se informa como exito', t => {
  const e = escenario(t, { docker: { fallarAlter: true } });
  assert.equal(e.ejecutar('-Cambiar', 'MYSQL_PASSWORD').codigo, 1);
  assert.equal(e.leer(), e.original);
  assert.equal(e.copias().length, 1);
});

test('rotacion expresa de MySQL se persiste sin exponer claves', t => {
  const e = escenario(t);
  const r = e.ejecutar('-Cambiar', 'MYSQL_PASSWORD');
  assert.equal(r.codigo, 0, r.salida);
  assert.notEqual(entorno(e.leer()).MYSQL_PASSWORD, propios.MYSQL_PASSWORD);
  const finales = JSON.parse(fs.readFileSync(path.join(e.carpeta, 'docker-final.json'), 'utf8'));
  assert.equal(finales.aplicacion, entorno(e.leer()).MYSQL_PASSWORD);
  assert.ok(!r.salida.includes(finales.aplicacion));
});

test('cifrado no se rota sin reconocimiento adicional', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar('-Cambiar', 'CIFRADO_LLAVE').codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('RabbitMQ existente no se desincroniza desde el archivo', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar('-Cambiar', 'RABBIT_PASSWORD').codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('un nombre de secreto desconocido falla sin mostrar su contenido', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar('-Cambiar', 'NO_EXISTE').codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('perfil con texto ejecutable no se evalua', t => {
  const e = escenario(t);
  const r = e.ejecutar('-Proveedores', e.perfil('SMTP_PASSWORD=$(Write-Host NO_EJECUTAR)\n'));
  assert.equal(r.codigo, 1);
  assert.ok(!r.salida.includes('NO_EJECUTAR'));
  assert.equal(e.leer(), e.original);
});

test('JWT demasiado corto falla antes de modificar el entorno', t => {
  const e = escenario(t, { valores: { JWT_SECRET: 'corto' } });
  assert.equal(e.ejecutar().codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('verificacion detecta cifrado distinto del contenedor', t => {
  const e = escenario(t, { docker: { servicio: true, claveServicio: propios.MYSQL_PASSWORD, cifrado: 'otro-cifrado-ficticio' } });
  const r = e.ejecutar('-Verificar');
  assert.equal(r.codigo, 1);
  assert.match(r.salida, /CIFRADO_LLAVE/);
});

test('la terminal tampoco puede cambiar silenciosamente el usuario MySQL', t => {
  const e = escenario(t, { ambiente: { MYSQL_USER: 'otro' } });
  assert.equal(e.ejecutar().codigo, 1);
  assert.equal(e.leer(), e.original);
});

for (const usuario of ['TBRIDGE', 'Tbridge']) {
  test('MYSQL_USER ' + usuario + ' se rechaza antes de consultar MySQL o escribir', t => {
    const e = escenario(t, { valores: { MYSQL_USER: usuario } });
    for (const opciones of [[], ['-Verificar']]) {
      const r = e.ejecutar(...opciones);
      assert.equal(r.codigo, 1);
      assert.match(r.salida, /MYSQL_USER=tbridge/);
      assert.ok(r.llamadas.every(l => l.argumentos[0] !== 'exec'));
      assert.equal(e.leer(), e.original);
      assert.equal(e.copias().length, 0);
    }
  });
}

test('MYSQL_USER ausente o vacio conserva el valor predeterminado de Compose', t => {
  for (const ausente of [true, false]) {
    const e = escenario(t, { valores: { MYSQL_USER: '' }, transformar: texto => ausente ? texto.replace(/^MYSQL_USER=.*\r?\n?/m, '') : texto });
    assert.equal(e.ejecutar('-Verificar').codigo, 0);
    assert.equal(e.leer(), e.original);
    assert.equal(e.ejecutar().codigo, 0);
    assert.equal(entorno(e.leer()).MYSQL_USER, ausente ? 'tbridge' : '');
    for (const nombre of internos) assert.equal(entorno(e.leer())[nombre], propios[nombre]);
  }
});

test('archivo alternativo solo se admite en modo de verificacion', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar('-ArchivoParaVerificar', e.archivo).codigo, 1);
  assert.equal(e.ejecutar('-Verificar', '-ArchivoParaVerificar', e.archivo).codigo, 0);
  assert.equal(e.leer(), e.original);
});

test('un valor literal con dolar se importa sin interpolarlo', t => {
  const e = escenario(t);
  const r = e.ejecutar('-Proveedores', e.perfil("SMTP_PASSWORD='ficticia$sinInterpolar'\n"));
  assert.equal(r.codigo, 0, r.salida);
  assert.equal(entorno(e.leer()).SMTP_PASSWORD, "'ficticia$sinInterpolar'");
});

test('perfil duplicado o con comillas incompletas se rechaza sin cambios', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar('-Proveedores', e.perfil('KHIPU_LLAVE=uno\nKHIPU_LLAVE=dos\n')).codigo, 1);
  assert.equal(e.ejecutar('-Proveedores', e.perfil("KHIPU_LLAVE='incompleto\n")).codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('verificar restaura MYSQL_PWD si ya existia en la terminal', t => {
  const e = escenario(t, { ambiente: { MYSQL_PWD: 'valor-previo-ficticio' } });
  assert.equal(e.ejecutar('-Verificar').codigo, 0);
  const finales = JSON.parse(fs.readFileSync(path.join(e.carpeta, 'docker-final.json'), 'utf8'));
  assert.equal(finales.pwdRestaurado, 'valor-previo-ficticio');
});

test('detecta el contenedor MySQL pendiente de recrear despues de una rotacion', t => {
  const e = escenario(t, { docker: { configAplicacion: 'configuracion-antigua-ficticia' } });
  const r = e.ejecutar('-Verificar');
  assert.equal(r.codigo, 1);
  assert.match(r.salida, /mysql conserva otro MYSQL_PASSWORD/);
  assert.equal(e.leer(), e.original);
});

test('ordenar conserva variables nuevas, valores y comentarios sin llamar a Docker', t => {
  const e = escenario(t, {
    valores: { GROQ_API_KEY: 'groq-ficticio-privado', GEMINI_API_KEY: 'gemini-ficticio-privado', LLM_ORDEN: 'groq,gemini', NUEVA_VARIABLE: 'valor-propio' },
    transformar: texto => '# Nota inicial propia\n' + texto + '\n# Nota final propia\n',
    docker: { sinMotor: true },
  });
  const r = e.ejecutar('-Ordenar');
  assert.equal(r.codigo, 0, r.salida);
  assert.deepEqual(entorno(e.leer()), entorno(e.original));
  for (const comentario of e.original.split(/\r?\n/).filter(l => /^\s*#/.test(l))) assert.ok(e.leer().includes(comentario));
  assert.match(e.leer(), /\[entorno:ia\]/);
  assert.match(e.leer(), /\[entorno:otras\]/);
  assert.equal(r.llamadas.length, 0);
  assert.equal(e.copias().length, 1);
  assert.equal(fs.readFileSync(path.join(e.copias()[0], '.env'), 'utf8'), e.original);
  assert.ok(!r.salida.includes('groq-ficticio-privado'));
  assert.ok(!r.salida.includes('gemini-ficticio-privado'));
});

test('ordenar dos veces es idempotente y no duplica respaldos ni cabeceras', t => {
  const e = escenario(t);
  assert.equal(e.ejecutar('-Ordenar').codigo, 0);
  const ordenado = e.leer();
  assert.equal(e.ejecutar('-Ordenar').codigo, 0);
  assert.equal(e.leer(), ordenado);
  assert.equal(e.copias().length, 1);
});

test('ordenar no completa ni genera claves que falten', t => {
  const e = escenario(t, { valores: { MYSQL_PASSWORD: '' }, transformar: texto => texto.replace(/^XAI_MODEL=.*\r?\n?/m, '') });
  assert.equal(e.ejecutar('-Ordenar').codigo, 0);
  assert.deepEqual(entorno(e.leer()), entorno(e.original));
});

test('ordenar exige archivo existente y excluye operaciones de preparacion', t => {
  const ausente = escenario(t, { existe: false });
  assert.equal(ausente.ejecutar('-Ordenar').codigo, 1);
  assert.equal(fs.existsSync(ausente.archivo), false);
  const e = escenario(t);
  for (const opcion of ['-Verificar', '-Renovar', '-ReemplazarProveedores']) assert.equal(e.ejecutar('-Ordenar', opcion).codigo, 1);
  assert.equal(e.leer(), e.original);
});

test('ordena un archivo alternativo sin escribir en la copia del preparador', t => {
  const e = escenario(t);
  const directorio = path.join(e.raiz, 'otra copia');
  fs.mkdirSync(directorio);
  const externo = path.join(directorio, '.env');
  fs.writeFileSync(externo, e.original);
  assert.equal(e.ejecutar('-ArchivoParaOrdenar', externo).codigo, 1);
  assert.equal(e.ejecutar('-Ordenar', '-ArchivoParaOrdenar', externo).codigo, 0);
  assert.deepEqual(entorno(fs.readFileSync(externo, 'utf8')), entorno(e.original));
  assert.equal(e.leer(), e.original);
  assert.equal(fs.readdirSync(directorio).filter(n => n.startsWith('.env.')).length, 0);
});

test('preparar conserva la configuracion IA existente aunque no este en la plantilla activa', t => {
  const e = escenario(t, { valores: { GROQ_API_KEY: 'groq-ficticio-privado', GEMINI_API_KEY: 'gemini-ficticio-privado', OLLAMA_MODEL: 'modelo-local-propio', LLM_ORDEN: 'groq,gemini' } });
  assert.equal(e.ejecutar().codigo, 0);
  assert.deepEqual(entorno(e.leer()), entorno(e.original));
});

test('perfil admite las trece variables de la cascada sin generar llaves de proveedor', t => {
  const e = escenario(t);
  const ia = {
    LLM_ORDEN: 'groq,gemini', LLM_ENFRIAMIENTO_MINUTOS: '60',
    OLLAMA_BASE_URL: 'http://host.docker.internal:11434/v1', OLLAMA_MODEL: 'modelo-local-propio', OLLAMA_TIMEOUT: '60',
    GROQ_API_KEY: 'groq-ficticio-privado', GROQ_BASE_URL: 'https://groq.example/v1', GROQ_MODEL: 'modelo-groq-propio', GROQ_TIMEOUT: '30',
    GEMINI_API_KEY: 'gemini-ficticio-privado', GEMINI_BASE_URL: 'https://gemini.example/v1', GEMINI_MODEL: 'modelo-gemini-propio', GEMINI_TIMEOUT: '30',
  };
  const perfil = e.perfil(Object.entries(ia).map(([k, v]) => `${k}=${v}`).join('\n') + '\n');
  const r = e.ejecutar('-Proveedores', perfil);
  assert.equal(r.codigo, 0, r.salida);
  for (const [k, v] of Object.entries(ia)) assert.equal(entorno(e.leer())[k], v);
  assert.ok(!r.salida.includes(ia.GROQ_API_KEY));
  assert.ok(!r.salida.includes(ia.GEMINI_API_KEY));
  for (const nombre of internos) assert.equal(entorno(e.leer())[nombre], propios[nombre]);
});

for (const [nombre, variablesIa, codigo] of [
  ['sin variables nuevas', {}, 1],
  ['con valores distintos', { LLM_ORDEN: 'ollama' }, 1],
  ['alineado', { LLM_ORDEN: 'groq,gemini', GROQ_API_KEY: 'groq-ficticio-privado' }, 0],
]) {
  test('verificar comprueba la configuracion de ms-ai: ' + nombre, t => {
    const e = escenario(t, { valores: { LLM_ORDEN: 'groq,gemini', GROQ_API_KEY: 'groq-ficticio-privado' }, docker: { ia: true, variablesIa } });
    const r = e.ejecutar('-Verificar');
    assert.equal(r.codigo, codigo, r.salida);
    assert.equal(e.leer(), e.original);
    assert.ok(!r.salida.includes('groq-ficticio-privado'));
  });
}

test('perfil importa la cuenta del Banco Central sin exponerla ni cambiar secretos internos', t => {
  const e = escenario(t);
  const r = e.ejecutar('-Proveedores', e.perfil("BCENTRAL_USER=cuenta-bde@example.com\nBCENTRAL_PASS='bc-ficticia$con-simbolo'\n"));
  assert.equal(r.codigo, 0, r.salida);
  assert.equal(entorno(e.leer()).BCENTRAL_USER, 'cuenta-bde@example.com');
  assert.equal(entorno(e.leer()).BCENTRAL_PASS, "'bc-ficticia$con-simbolo'");
  assert.ok(!r.salida.includes('cuenta-bde@example.com'));
  assert.ok(!r.salida.includes('bc-ficticia'));
  for (const nombre of internos) assert.equal(entorno(e.leer())[nombre], propios[nombre]);
});

for (const linea of ['BCENTRAL_USER=cuenta-bde@example.com\n', 'BCENTRAL_PASS=bc-ficticia-no-compartir\n']) {
  test('Banco Central rechaza un perfil con solo ' + linea.split('=')[0], t => {
    const e = escenario(t);
    const r = e.ejecutar('-Proveedores', e.perfil(linea));
    assert.equal(r.codigo, 1);
    assert.equal(e.leer(), e.original);
    assert.equal(e.copias().length, 0);
  });
  test('no mezcla un perfil parcial de ' + linea.split('=')[0] + ' con otra cuenta existente', t => {
    const e = escenario(t, { valores: { BCENTRAL_USER: 'anterior@example.com', BCENTRAL_PASS: 'bc-anterior-ficticia' } });
    const r = e.ejecutar('-Proveedores', e.perfil(linea), '-ReemplazarProveedores');
    assert.equal(r.codigo, 1);
    assert.match(r.salida, /BCENTRAL_USER y BCENTRAL_PASS juntos/);
    assert.equal(e.leer(), e.original);
    assert.equal(e.copias().length, 0);
  });
}

test('una cuenta BDE propia no se reemplaza sin opcion expresa', t => {
  const e = escenario(t, { valores: { BCENTRAL_USER: 'anterior@example.com', BCENTRAL_PASS: 'bc-anterior-ficticia' } });
  assert.equal(e.ejecutar('-Proveedores', e.perfil('BCENTRAL_USER=nueva@example.com\nBCENTRAL_PASS=bc-nueva-ficticia\n')).codigo, 1);
  assert.equal(e.leer(), e.original);
});

for (const [nombre, variablesBanco, codigo] of [
  ['sin aplicar', {}, 1],
  ['clave anterior', { BCENTRAL_USER: 'cuenta-bde@example.com', BCENTRAL_PASS: 'bc-anterior-ficticia' }, 1],
  ['alineado', { BCENTRAL_USER: 'cuenta-bde@example.com', BCENTRAL_PASS: 'bc-ficticia-no-compartir' }, 0],
]) {
  test('verificar comprueba Banco Central en ms-payments: ' + nombre, t => {
    const e = escenario(t, { valores: { BCENTRAL_USER: 'cuenta-bde@example.com', BCENTRAL_PASS: 'bc-ficticia-no-compartir' }, docker: { pagos: true, variablesBanco } });
    const r = e.ejecutar('-Verificar');
    assert.equal(r.codigo, codigo, r.salida);
    assert.ok(!r.salida.includes('cuenta-bde@example.com'));
    assert.ok(!r.salida.includes('bc-ficticia-no-compartir'));
    assert.equal(e.leer(), e.original);
  });
}
