const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const cp = require('node:child_process');
const { parseEnv } = require('node:util');

function opciones(args) {
  const o = { aplicar: false };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--aplicar' && !o.aplicar) o.aplicar = true;
    else if (args[i] === '--paquete' && args[i + 1] && !o.paquete) o.paquete = path.resolve(args[++i]);
    else throw new Error('Use --paquete con una carpeta privada y, solo para restaurar, --aplicar.');
  }
  if (!o.paquete) throw new Error('Falta --paquete. El respaldo nunca se descarga desde Git.');
  return o;
}

function comando(programa, args, extra = {}) {
  try { return cp.execFileSync(programa, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'], timeout: 180000, maxBuffer: 32 * 1024 * 1024, ...extra }).toString('utf8'); }
  catch { throw new Error('Fallo una operacion de restauracion. Se omiten registros privados; revise el estado antes de repetir.'); }
}

function restaurar(o, ejecutar = comando) {
  const repo = o.repo || path.resolve(__dirname, '..');
  const paquete = fs.realpathSync(o.paquete);
  for (let dir = paquete; ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, '.git'))) throw new Error('El paquete privado debe estar fuera de cualquier repositorio.');
    if (dir === path.dirname(dir)) break;
  }
  const manifiesto = JSON.parse(fs.readFileSync(path.join(paquete, 'manifiesto.json'), 'utf8'));
  if (manifiesto.version !== 1 || manifiesto.verificacionRestauracion !== 'correcta-en-copia-aislada'
      || JSON.stringify(manifiesto.bases) !== JSON.stringify(['tb_auth', 'tb_debt', 'tb_payments'])) {
    throw new Error('El manifiesto no corresponde a un respaldo completo y probado.');
  }
  const archivos = {};
  for (const nombre of ['.env', 'bases.sql']) {
    archivos[nombre] = fs.readFileSync(path.join(paquete, nombre));
    if (crypto.createHash('sha256').update(archivos[nombre]).digest('hex') !== manifiesto.archivos[nombre]) {
      throw new Error('La integridad del paquete no coincide. No se restaura.');
    }
  }
  const e = parseEnv(archivos['.env'].toString('utf8'));
  for (const n of ['MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'JWT_SECRET', 'INTERNAL_KEY', 'CIFRADO_LLAVE', 'WEBHOOK_SECRET', 'RABBIT_PASSWORD']) {
    if (!e[n]) throw new Error('Falta un secreto interno en el respaldo. No se genera una llave sustituta.');
  }
  const mismoEquipo = manifiesto.equipoOrigen === (o.equipo || os.hostname());
  if (!o.aplicar) return { aplicado: false, paqueteIntegro: true, bloqueadoEnEquipoOrigen: mismoEquipo, requiereBasesVacias: true };
  if (mismoEquipo) throw new Error('Este es el equipo de origen. La restauracion se ejecuta SOLO en el otro PC.');
  if (fs.existsSync(path.join(repo, '.env'))) throw new Error('El destino ya tiene .env. No se reemplaza una instalacion existente.');
  if (fs.existsSync(path.join(paquete, 'estado-restauracion.json'))) throw new Error('Hay un intento previo. Revise el estado privado antes de repetir.');
  const rama = ejecutar('git', ['-C', repo, 'branch', '--show-current']).trim();
  const commit = ejecutar('git', ['-C', repo, 'rev-parse', 'HEAD']).trim();
  if (rama !== manifiesto.rama || commit !== manifiesto.commit) throw new Error('La rama o el commit no coinciden con el paquete.');
  const servicios = ejecutar('docker', ['ps', '--filter', 'label=com.docker.compose.project=tb_web', '--format', '{{.Label "com.docker.compose.service"}}']).trim().split(/\r?\n/).filter(Boolean);
  if (servicios.some(n => !['mysql', 'rabbitmq'].includes(n))) throw new Error('Hay servicios de aplicacion activos en el destino. No se restaura mientras pueden escribir o enviar correo.');
  const envReplica = { ...process.env, ...e };
  const mysqlEnv = { ...envReplica, MYSQL_PWD: e.MYSQL_ROOT_PASSWORD };
  const sql = consulta => {
    const r = ejecutar('docker', ['exec', '-i', '-e', 'MYSQL_PWD', 'tbridge-db', 'mysql', '-uroot', '--batch', '--skip-column-names', '--raw'], { input: consulta, env: mysqlEnv }).trim();
    return r ? JSON.parse(r) : null;
  };
  ejecutar('docker', ['compose', '-p', 'tb_web', '--project-directory', repo, '--env-file', path.join(paquete, '.env'), '-f', path.join(repo, 'docker-compose.yml'), 'up', '-d', '--wait', 'mysql'], { env: envReplica });
  const tablas = sql("SELECT JSON_OBJECT('n',COUNT(*)) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('tb_auth','tb_debt','tb_payments');");
  if (tablas.n !== 0) throw new Error('Las bases del destino no estan vacias. No se borran ni sobrescriben; se requiere otra decision.');
  const estado = { fase: 'iniciada', commit, bases: manifiesto.bases };
  const guardarEstado = () => fs.writeFileSync(path.join(paquete, 'estado-restauracion.json'), JSON.stringify(estado, null, 2), { mode: 0o600, flush: true });
  guardarEstado();
  try {
    fs.writeFileSync(path.join(repo, '.env'), archivos['.env'], { flag: 'wx', mode: 0o600, flush: true });
    ejecutar('docker', ['exec', '-i', '-e', 'MYSQL_PWD', 'tbridge-db', 'mysql', '-uroot', '--binary-mode=1', '--default-character-set=utf8mb4'], { input: archivos['bases.sql'], env: mysqlEnv });
    const totales = sql("SELECT JSON_OBJECT('empresas',(SELECT COUNT(*) FROM tb_debt.organizations),'campanas',(SELECT COUNT(*) FROM tb_debt.campaigns),'deudores',(SELECT COUNT(*) FROM tb_debt.debtors),'deudas',(SELECT COUNT(*) FROM tb_debt.debts),'personal',(SELECT COUNT(*) FROM tb_auth.staff_users),'pagos',(SELECT COUNT(*) FROM tb_payments.payments));");
    if (Object.entries(manifiesto.conteosConsultadosDespuesDelSnapshot).some(([n, v]) => totales[n] !== v)) throw new Error('Los totales restaurados no coinciden.');
    estado.fase = 'restaurada'; guardarEstado();
    return { aplicado: true, basesRestauradas: 3, totales, serviciosDeAplicacionIniciados: false };
  } catch {
    estado.fase = 'pendiente-de-revision'; guardarEstado();
    throw new Error('La restauracion no se completo. No se inicia la aplicacion; revise el estado privado y no reintente a ciegas.');
  }
}

if (require.main === module) {
  try { console.log(JSON.stringify(restaurar(opciones(process.argv.slice(2))))); }
  catch (fallo) { console.error(fallo.message); process.exitCode = 1; }
}
module.exports = { opciones, restaurar };
