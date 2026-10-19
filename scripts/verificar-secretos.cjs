const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

function verificar(args, run = spawnSync) {
  let rango;
  if (args.length === 1 && args[0] === '--staged') rango = ['--pre-commit', '--staged'];
  else if (args.length === 2 && args[0] === '--base' && /^[a-f0-9]{40}$/.test(args[1])) rango = ['--log-opts=' + args[1] + '..HEAD'];
  else throw new Error('Use --staged o --base seguido del SHA completo de la base.');
  const repo = path.resolve(__dirname, '..');
  const temporal = fs.mkdtempSync(path.join(os.tmpdir(), 'tb-secretos-'));
  const reporte = path.join(temporal, 'reporte.json');
  try {
    const r = run(process.env.GITLEAKS || 'gitleaks', [
      'git', repo, ...rango, '--config', path.join(repo, '.gitleaks.toml'),
      '--gitleaks-ignore-path', path.join(temporal, 'sin-omisiones'), '--ignore-gitleaks-allow',
      '--redact=100', '--no-banner', '--no-color', '--log-level', 'error',
      '--report-format', 'json', '--report-path', reporte,
    ], { windowsHide: true, encoding: 'utf8', timeout: 180000, maxBuffer: 16 * 1024 * 1024 });
    if (r.error || ![0, 1].includes(r.status) || !fs.existsSync(reporte)) {
      throw new Error('No se pudo ejecutar Gitleaks. Instale la version documentada; no se muestran sus registros.');
    }
    let hallazgos;
    try { hallazgos = JSON.parse(fs.readFileSync(reporte, 'utf8')); }
    catch { throw new Error('Gitleaks no devolvio un reporte valido. No se muestra su contenido.'); }
    if (r.status === 0 && hallazgos.length !== 0) throw new Error('El resultado de Gitleaks no es consistente.');
    return { correcto: r.status === 0, cantidad: hallazgos.length,
      ubicaciones: hallazgos.slice(0, 30).map(h => ({ regla: h.RuleID, archivo: h.File.replace(/[\r\n]/g, '').replace(/[^/\s]+@[^/\s]+/g, '[correo]'), linea: h.StartLine })) };
  } finally {
    if (path.dirname(temporal) === os.tmpdir() && path.basename(temporal).startsWith('tb-secretos-')) fs.rmSync(temporal, { recursive: true, force: true });
  }
}

if (require.main === module) {
  try {
    const resultado = verificar(process.argv.slice(2));
    console.log(JSON.stringify(resultado));
    if (!resultado.correcto) process.exitCode = 1;
  } catch (fallo) { console.error(fallo.message); process.exitCode = 2; }
}
module.exports = { verificar };
