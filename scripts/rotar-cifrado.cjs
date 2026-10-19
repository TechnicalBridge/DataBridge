const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { parseEnv } = require('node:util');
const { spawnSync } = require('node:child_process');

function opciones(args) {
  const o = { aplicar: false, entorno: path.resolve('.env'), respaldos: null };
  let modo = false;
  for (let i = 0; i < args.length; i++) {
    if (['--aplicar', '--verificar'].includes(args[i])) {
      if (modo) throw new Error('Indique solo --verificar o --aplicar.');
      modo = true;
      o.aplicar = args[i] === '--aplicar';
    } else if (['--entorno', '--respaldos'].includes(args[i]) && args[i + 1] && !args[i + 1].startsWith('--')) {
      const clave = args[i].slice(2);
      o[clave] = path.resolve(args[++i]);
    } else throw new Error('Opcion no admitida. Use --verificar o --aplicar, --entorno y --respaldos.');
  }
  o.respaldos ||= path.join(path.dirname(path.dirname(o.entorno)), 'respaldos');
  return o;
}

function proponer(texto, nueva) {
  if ((texto.match(/^[ \t]*CIFRADO_LLAVE[ \t]*=/gm) || []).length !== 1) {
    throw new Error('CIFRADO_LLAVE debe aparecer una sola vez en el archivo.');
  }
  if (!/^[A-Za-z0-9_-]{64}$/.test(nueva)) throw new Error('La nueva llave no tiene el formato esperado.');
  return texto.replace(/^([ \t]*CIFRADO_LLAVE[ \t]*=)[^\r\n]*/m, (_, inicio) => inicio + nueva);
}

function ejecutar(programa, args, extra = {}) {
  const r = spawnSync(programa, args, { encoding: 'utf8', windowsHide: true, timeout: 240000, maxBuffer: 16 * 1024 * 1024, ...extra });
  return { codigo: r.error ? -1 : r.status, salida: typeof r.stdout === 'string' ? r.stdout : '' };
}

function rutaReal(ruta) {
  let existente = path.resolve(ruta);
  const pendientes = [];
  while (!fs.existsSync(existente)) {
    pendientes.unshift(path.basename(existente));
    const padre = path.dirname(existente);
    if (padre === existente) throw new Error('No se pudo resolver la carpeta de respaldos.');
    existente = padre;
  }
  return path.join(fs.realpathSync(existente), ...pendientes);
}

function rotar(o, comando = ejecutar) {
  const repo = fs.realpathSync(path.resolve(__dirname, '..'));
  const archivo = fs.realpathSync(o.entorno);
  const raizRespaldo = rutaReal(o.respaldos);
  const relativo = path.relative(repo, raizRespaldo);
  if (!relativo || (!relativo.startsWith('..' + path.sep) && relativo !== '..' && !path.isAbsolute(relativo))) {
    throw new Error('Los respaldos deben quedar fuera del repositorio.');
  }
  const texto = fs.readFileSync(archivo, 'utf8');
  const valores = parseEnv(texto);
  if (!valores.CIFRADO_LLAVE || !valores.MYSQL_PASSWORD || (valores.MYSQL_USER && valores.MYSQL_USER !== 'tbridge')) {
    throw new Error('Faltan las credenciales internas o MYSQL_USER no es tbridge.');
  }
  // Rechaza duplicados tambien en las variables usadas para autenticar.
  for (const nombre of ['MYSQL_PASSWORD', 'MYSQL_USER', 'CIFRADO_LLAVE']) {
    if ((texto.match(new RegExp('^[ \\t]*' + nombre + '[ \\t]*=', 'gm')) || []).length > 1) throw new Error('Hay variables internas duplicadas.');
  }
  for (let dir = raizRespaldo; ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, '.git'))) throw new Error('Los respaldos no pueden estar dentro de ningun repositorio.');
    if (dir === path.dirname(dir)) break;
  }
  const lock = path.join(raizRespaldo, '.rotacion-cifrado-' + crypto.createHash('sha256').update(archivo).digest('hex').slice(0, 16) + '.lock');
  if (o.aplicar && fs.existsSync(lock)) throw new Error('Hay una rotacion pendiente. Revise su diario antes de iniciar otra.');
  const cpArchivo = o.classpathArchivo || path.join(repo, 'ms-debt', 'target', 'classpath.txt');
  if (!fs.existsSync(cpArchivo)) throw new Error('Compile y pruebe ms-debt, y genere target/classpath.txt antes de continuar.');
  const classpath = path.join(repo, 'ms-debt', 'target', 'classes') + path.delimiter + fs.readFileSync(cpArchivo, 'utf8').trim();
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const nueva = crypto.randomBytes(48).toString('base64url');
  const propuesto = proponer(texto, nueva);
  const docker = (args, extra) => comando('docker', args, extra);
  const listado = docker(['ps', '-a', '--filter', 'label=com.docker.compose.project=tb_web', '--format', '{{.Names}}']);
  if (listado.codigo !== 0) throw new Error('Docker no permite comprobar el proyecto tb_web.');
  const nombres = listado.salida.trim().split(/\r?\n/).filter(Boolean);
  const inspeccion = docker(['inspect', ...nombres]);
  if (!nombres.length || inspeccion.codigo !== 0) throw new Error('No se encontraron los contenedores existentes de tb_web.');
  const contenedores = JSON.parse(inspeccion.salida);
  const servicio = rol => contenedores.find(c => c.Config.Labels['com.docker.compose.project'] === 'tb_web' && c.Config.Labels['com.docker.compose.service'] === rol);
  const base = servicio('mysql');
  const deuda = servicio('ms-debt');
  if (!base?.State.Running || !deuda?.State.Running) throw new Error('MySQL y ms-debt deben estar iniciados antes de comprobar.');
  const envDeuda = Object.fromEntries(deuda.Config.Env.map(v => { const i = v.indexOf('='); return [v.slice(0, i), v.slice(i + 1)]; }));
  if (envDeuda.CIFRADO_LLAVE !== valores.CIFRADO_LLAVE || envDeuda.DB_PASSWORD !== valores.MYSQL_PASSWORD) {
    throw new Error('El archivo no coincide con las credenciales de ms-debt. No se modifica nada.');
  }
  const puerto = base.NetworkSettings.Ports['3306/tcp']?.find(p => ['127.0.0.1', '0.0.0.0'].includes(p.HostIp))?.HostPort;
  if (!/^\d+$/.test(puerto || '')) throw new Error('La herramienta local requiere un puerto MySQL publicado en este equipo.');
  const javaEnv = { ...process.env };
  for (const k of ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']) delete javaEnv[k];
  const llamarJava = (modo, anterior, siguiente) => {
    const r = comando(java, ['-cp', classpath, 'com.tbridge.debt.mantenimiento.RotacionCifrado'], {
      env: javaEnv, input: JSON.stringify({ modo, url: `jdbc:mysql://127.0.0.1:${puerto}/tb_debt?serverTimezone=UTC`, usuario: 'tbridge', password: valores.MYSQL_PASSWORD, anterior, nueva: siguiente }),
    });
    let datos;
    try { datos = JSON.parse(r.salida.trim()); } catch { throw new Error('La herramienta Java no devolvio un resultado valido. No se muestran sus registros.'); }
    if (r.codigo !== 0 || !datos.correcto || !Number.isSafeInteger(datos.filas)) throw new Error('No se pudo comprobar la rotacion. Revise la instalacion o el respaldo sin iniciar el servicio con otra llave.');
    return datos.filas;
  };
  const cantidad = llamarJava('verificar', valores.CIFRADO_LLAVE, nueva);
  if (!o.aplicar) return { aplicado: false, suscripciones: cantidad };
  const fuentes = deuda.Config.Labels['com.docker.compose.project.config_files']?.split(',') || [];
  if (!fuentes.length || fuentes.some(f => !path.isAbsolute(f) || !fs.existsSync(f))) throw new Error('No se pudieron recuperar los archivos Compose de la instalacion actual.');
  fs.mkdirSync(raizRespaldo, { recursive: true });
  const carpeta = path.join(raizRespaldo, 'cifrado-' + new Date().toISOString().replace(/[:.]/g, '-') + '-' + crypto.randomUUID());
  try { fs.writeFileSync(lock, carpeta, { flag: 'wx', mode: 0o600 }); }
  catch { throw new Error('Hay una rotacion pendiente. Revise su diario antes de iniciar otra.'); }
  let detenido = false;
  let migracionIntentada = false;
  let completo = false;
  let fase = 'preparando';
  let ultimaFaseSegura = fase;
  const diario = () => {
    if (!['pendiente-de-recuperacion', 'fallo-sin-migracion'].includes(fase)) ultimaFaseSegura = fase;
    fs.writeFileSync(path.join(carpeta, 'estado.json'), JSON.stringify({ fase, ultimaFaseSegura, entorno: archivo, servicio: deuda.Name.slice(1), suscripciones: cantidad }, null, 2), { mode: 0o600, flush: true });
  };
  try {
    // El preflight ocurrio antes de tomar el bloqueo: otro proceso pudo terminar mientras tanto.
    if (fs.readFileSync(archivo, 'utf8') !== texto) throw new Error('El archivo cambio durante la comprobacion previa. No se detienen servicios.');
    const reinspeccion = docker(['inspect', base.Name.slice(1), deuda.Name.slice(1)]);
    let vigentes;
    try { vigentes = JSON.parse(reinspeccion.salida); } catch { throw new Error('No se pudo revalidar la instalacion antes de detenerla.'); }
    if (reinspeccion.codigo !== 0 || [base, deuda].some(c => !vigentes.some(v => v.Id === c.Id && v.Image === c.Image && v.State.Running))) {
      throw new Error('La instalacion cambio durante la comprobacion previa. No se detienen servicios.');
    }
    fs.mkdirSync(carpeta, { mode: 0o700 });
    fs.writeFileSync(path.join(carpeta, '.env'), texto, { flag: 'wx', mode: 0o600, flush: true });
    fs.writeFileSync(path.join(carpeta, '.env.propuesto'), propuesto, { flag: 'wx', mode: 0o600, flush: true });
    diario();
    if (docker(['stop', deuda.Name.slice(1)]).codigo !== 0) throw new Error('No se pudo detener solo ms-debt.');
    detenido = true;
    const fd = fs.openSync(path.join(carpeta, 'tb_debt.sql'), 'wx', 0o600);
    let dump;
    try {
      dump = docker(['exec', '-e', 'MYSQL_PWD', base.Name.slice(1), 'mysqldump', '-utbridge', '--single-transaction', '--no-tablespaces', '--set-gtid-purged=OFF', '--hex-blob', 'tb_debt'], {
        env: { ...process.env, MYSQL_PWD: valores.MYSQL_PASSWORD }, stdio: ['ignore', fd, 'pipe'],
      });
      fs.fsyncSync(fd);
    } finally { fs.closeSync(fd); }
    if (dump.codigo !== 0 || fs.statSync(path.join(carpeta, 'tb_debt.sql')).size === 0) throw new Error('El respaldo MySQL no termino correctamente. No se migra.');
    fase = 'respaldado'; diario();
    migracionIntentada = true;
    const rotadas = llamarJava('aplicar', valores.CIFRADO_LLAVE, nueva);
    fase = 'base-migrada'; diario();
    // Antes de reemplazar, detecta cambios del usuario durante el mantenimiento.
    if (fs.readFileSync(archivo, 'utf8') !== texto) throw new Error('El archivo cambio durante la rotacion. El servicio queda detenido para recuperar con el respaldo.');
    const temporal = archivo + '.rotacion.tmp';
    fs.writeFileSync(temporal, propuesto, { flag: 'wx', mode: 0o600, flush: true });
    fs.renameSync(temporal, archivo);
    fase = 'entorno-actualizado'; diario();
    const override = path.join(carpeta, 'compose-rotacion.json');
    const esperado = { ...envDeuda, CIFRADO_LLAVE: nueva };
    const literalCompose = Object.fromEntries(Object.entries(esperado).map(([k, v]) => [k, v.replaceAll('$', () => '$$')]));
    fs.writeFileSync(override, JSON.stringify({ services: { 'ms-debt': { image: deuda.Image, environment: literalCompose } } }), { mode: 0o600, flush: true });
    const args = ['compose', '-p', 'tb_web', '--env-file', archivo];
    for (const fuente of fuentes) args.push('-f', fuente);
    args.push('-f', override, '--profile', 'app', 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', '--wait', '--wait-timeout', '180', 'ms-debt');
    if (docker(args).codigo !== 0) throw new Error('La base y el entorno ya tienen la nueva llave, pero ms-debt no quedo saludable. Revise el diario privado.');
    const posterior = docker(['inspect', deuda.Name.slice(1)]);
    if (posterior.codigo !== 0) throw new Error('No se pudo verificar ms-debt despues de recrearlo.');
    const actual = JSON.parse(posterior.salida)[0];
    const variablesActuales = new Set(actual.Config.Env);
    if (actual.Image !== deuda.Image || Object.entries(esperado).some(([k, v]) => !variablesActuales.has(k + '=' + v))) throw new Error('La imagen o las variables de ms-debt no coinciden con las esperadas.');
    if (llamarJava('verificar', nueva, crypto.randomBytes(48).toString('base64url')) !== rotadas) throw new Error('La comprobacion final de suscripciones no coincide.');
    fase = 'completa'; diario();
    completo = true;
    return { aplicado: true, suscripciones: rotadas, respaldo: carpeta };
  } catch (fallo) {
    if (migracionIntentada && detenido) docker(['stop', deuda.Name.slice(1)]);
    fase = migracionIntentada ? 'pendiente-de-recuperacion' : 'fallo-sin-migracion';
    try { diario(); } catch { }
    if (detenido && !migracionIntentada) docker(['start', deuda.Name.slice(1)]);
    const detalle = fs.existsSync(carpeta) ? 'Consulte el diario privado en ' + carpeta + '. ' : 'No se inicio la migracion. ';
    throw new Error('Rotacion interrumpida. ' + detalle + fallo.message);
  } finally {
    if (completo || !migracionIntentada) fs.unlinkSync(lock);
  }
}

if (require.main === module) {
  try { console.log(JSON.stringify(rotar(opciones(process.argv.slice(2))))); }
  catch (fallo) { console.error(fallo.message); process.exitCode = 1; }
}
module.exports = { opciones, proponer, rotar };
