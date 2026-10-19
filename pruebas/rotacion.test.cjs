const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { opciones, proponer, rotar } = require('../scripts/rotar-cifrado.cjs');

function escenario(t, fallo) {
  const raiz = fs.mkdtempSync(path.join(os.tmpdir(), 'tb-rotacion-'));
  const archivo = path.join(raiz, '.env');
  const cp = path.join(raiz, 'classpath.txt');
  const compose = path.join(raiz, 'compose.yml');
  const original = '# conservar\r\nCIFRADO_LLAVE=anterior-ficticia\r\nMYSQL_PASSWORD=base-ficticia\r\nMERCADOPAGO_ACCESS_TOKEN=token-ficticio\r\n';
  fs.writeFileSync(archivo, original);
  fs.writeFileSync(cp, 'dependencia-ficticia.jar');
  fs.writeFileSync(compose, 'services: {}');
  let clave = 'anterior-ficticia';
  let migrada = false;
  const llamadas = [];
  const contenedores = () => [
    { Id: 'base-ficticia', Name: '/tbridge-db', State: { Running: true }, Config: { Labels: { 'com.docker.compose.project': 'tb_web', 'com.docker.compose.service': 'mysql' } }, NetworkSettings: { Ports: { '3306/tcp': [{ HostIp: '127.0.0.1', HostPort: '3308' }] } } },
    { Id: 'deuda-ficticia', Name: '/tb_web-ms-debt-1', Image: 'sha256:imagen-ficticia', State: { Running: true }, Config: { Env: ['CIFRADO_LLAVE=' + clave, 'DB_PASSWORD=base-ficticia', 'OTRA=literal$sinInterpolar'], Labels: { 'com.docker.compose.project': 'tb_web', 'com.docker.compose.service': 'ms-debt', 'com.docker.compose.project.config_files': compose } } },
  ];
  const comando = (programa, args, extra) => {
    llamadas.push({ programa: path.basename(programa), args });
    if (programa !== 'docker') {
      const peticion = JSON.parse(extra.input);
      if (peticion.modo === 'aplicar') {
        if (fallo === 'java') return { codigo: 3, salida: '{"correcto":false,"estadoIncierto":true}' };
        assert.equal(fs.readFileSync(archivo, 'utf8'), original);
        migrada = true;
        return { codigo: 0, salida: '{"correcto":true,"filas":1}' };
      }
      return { codigo: 0, salida: '{"correcto":true,"filas":1}' };
    }
    if (args[0] === 'ps') return { codigo: 0, salida: 'tbridge-db\ntb_web-ms-debt-1\n' };
    if (args[0] === 'inspect') return { codigo: 0, salida: JSON.stringify(args.length === 2 ? [contenedores()[1]] : contenedores()) };
    if (args[0] === 'exec') {
      if (fallo === 'dump') return { codigo: 1, salida: '' };
      fs.writeSync(extra.stdio[1], '-- respaldo ficticio\n');
    }
    if (args[0] === 'compose') {
      if (fallo === 'compose') return { codigo: 1, salida: '' };
      const override = JSON.parse(fs.readFileSync(args[args.lastIndexOf('-f') + 1], 'utf8'));
      assert.equal(override.services['ms-debt'].image, 'sha256:imagen-ficticia');
      assert.equal(override.services['ms-debt'].environment.OTRA, 'literal$$sinInterpolar');
      clave = override.services['ms-debt'].environment.CIFRADO_LLAVE;
    }
    return { codigo: 0, salida: '' };
  };
  t.after(() => {
    assert.equal(path.dirname(raiz), os.tmpdir());
    assert.ok(path.basename(raiz).startsWith('tb-rotacion-'));
    fs.rmSync(raiz, { recursive: true, force: true });
  });
  return { original, archivo, raiz, comando, llamadas, migrada: () => migrada, o: { aplicar: false, entorno: archivo, respaldos: path.join(raiz, 'respaldos'), classpathArchivo: cp } };
}

test('por omision solo verifica y rechaza opciones ambiguas', () => {
  assert.equal(opciones([]).aplicar, false);
  assert.throws(() => opciones(['--aplicar', '--verificar']));
  assert.throws(() => opciones(['--entorno']));
  assert.throws(() => opciones(['--otra']));
});

test('proponer cambia solo cifrado y conserva comentarios y saltos', () => {
  const texto = '# conservar\r\nCIFRADO_LLAVE=vieja-ficticia\r\nOTRA=igual\r\n';
  assert.equal(proponer(texto, 'x'.repeat(64)), texto.replace('vieja-ficticia', 'x'.repeat(64)));
  assert.throws(() => proponer(texto + 'CIFRADO_LLAVE=duplicada\n', 'x'.repeat(64)));
  assert.throws(() => proponer(texto, 'corta'));
});

test('verificacion no cambia archivos, servicios ni base', t => {
  const e = escenario(t);
  assert.deepEqual(rotar(e.o, e.comando), { aplicado: false, suscripciones: 1 });
  assert.equal(fs.readFileSync(e.archivo, 'utf8'), e.original);
  assert.equal(fs.existsSync(e.o.respaldos), false);
  assert.equal(e.migrada(), false);
  assert.ok(e.llamadas.every(x => x.programa !== 'docker' || ['inspect', 'ps'].includes(x.args[0])));
});

test('aplicar respalda y migra antes de cambiar entorno y recrea solo deuda sin construir', t => {
  const e = escenario(t);
  const r = rotar({ ...e.o, aplicar: true }, e.comando);
  assert.equal(r.aplicado, true);
  assert.equal(fs.readFileSync(path.join(r.respaldo, '.env'), 'utf8'), e.original);
  assert.equal(JSON.parse(fs.readFileSync(path.join(r.respaldo, 'estado.json'))).fase, 'completa');
  assert.match(fs.readFileSync(e.archivo, 'utf8'), /MERCADOPAGO_ACCESS_TOKEN=token-ficticio/);
  assert.ok(e.migrada());
  const c = e.llamadas.find(x => x.args[0] === 'compose').args;
  for (const flag of ['--no-deps', '--no-build', '--force-recreate', '--wait']) assert.ok(c.includes(flag));
  assert.equal(c[c.length - 1], 'ms-debt');
  assert.equal(c[c.indexOf('--pull') + 1], 'never');
  assert.ok(e.llamadas.flatMap(x => x.args).every(x => !/anterior-ficticia|base-ficticia|token-ficticio/.test(x)));
});

test('un respaldo fallido no migra ni cambia el entorno y reinicia el mismo servicio', t => {
  const e = escenario(t, 'dump');
  assert.throws(() => rotar({ ...e.o, aplicar: true }, e.comando), /respaldo MySQL/);
  assert.equal(e.migrada(), false);
  assert.equal(fs.readFileSync(e.archivo, 'utf8'), e.original);
  assert.ok(e.llamadas.some(x => x.args[0] === 'start' && x.args[1] === 'tb_web-ms-debt-1'));
});

for (const fallo of ['java', 'compose']) {
  test('fallo en ' + fallo + ' deja diario y bloqueo sin iniciar con otra llave', t => {
    const e = escenario(t, fallo);
    assert.throws(() => rotar({ ...e.o, aplicar: true }, e.comando), /diario privado/);
    assert.ok(fs.readdirSync(e.o.respaldos).some(x => x.endsWith('.lock')));
    assert.ok(!e.llamadas.some(x => x.args[0] === 'start'));
    assert.throws(() => rotar({ ...e.o, aplicar: true }, e.comando), /rotacion pendiente/);
  });
}

test('rechaza respaldos en cualquier repositorio antes de llamar a Docker', t => {
  const e = escenario(t);
  fs.writeFileSync(path.join(e.raiz, '.git'), 'gitdir: ficticio');
  assert.throws(() => rotar(e.o, e.comando), /ningun repositorio/);
  assert.equal(e.llamadas.length, 0);
});

test('un enlace a una subcarpeta de otro repositorio no permite respaldar secretos ahi', t => {
  const e = escenario(t);
  const ajeno = path.join(e.raiz, 'repositorio');
  const destino = path.join(ajeno, 'datos', 'respaldos');
  fs.mkdirSync(destino, { recursive: true });
  fs.writeFileSync(path.join(ajeno, '.git'), 'gitdir: ficticio');
  const enlace = path.join(e.raiz, 'enlace');
  fs.symlinkSync(path.join(ajeno, 'datos'), enlace, process.platform === 'win32' ? 'junction' : 'dir');
  assert.throws(() => rotar({ ...e.o, respaldos: path.join(enlace, 'respaldos') }, e.comando), /repositorio/);
  assert.equal(e.llamadas.length, 0);
  assert.deepEqual(fs.readdirSync(destino), []);
});

test('un cambio de archivo antes de tomar el bloqueo impide parar el servicio y migrar', t => {
  const e = escenario(t);
  const comando = (programa, args, extra) => {
    const r = e.comando(programa, args, extra);
    if (programa !== 'docker' && JSON.parse(extra.input).modo === 'verificar') fs.appendFileSync(e.archivo, '# cambio externo\n');
    return r;
  };
  assert.throws(() => rotar({ ...e.o, aplicar: true }, comando));
  assert.equal(e.migrada(), false);
  assert.ok(!e.llamadas.some(x => x.args[0] === 'stop'));
  assert.ok(fs.readFileSync(e.archivo, 'utf8').endsWith('# cambio externo\n'));
});

test('una instancia reemplazada durante el preflight no se detiene ni se migra', t => {
  const e = escenario(t);
  let verificada = false;
  const comando = (programa, args, extra) => {
    const r = e.comando(programa, args, extra);
    if (programa !== 'docker') verificada = true;
    if (verificada && programa === 'docker' && args[0] === 'inspect') {
      const datos = JSON.parse(r.salida);
      datos.find(c => c.Name === '/tb_web-ms-debt-1').Id = 'instancia-ficticia-reemplazada';
      return { ...r, salida: JSON.stringify(datos) };
    }
    return r;
  };
  assert.throws(() => rotar({ ...e.o, aplicar: true }, comando));
  assert.equal(e.migrada(), false);
  assert.ok(!e.llamadas.some(x => x.args[0] === 'stop'));
});
