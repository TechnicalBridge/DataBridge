const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

for (const [nombre, args, contenido] of [
  ['TambienCifrado', ['-TambienCifrado'], 'CIFRADO_LLAVE=ficticia-conservar\n'],
  ['cambio forzado', ['-Cambiar', 'CIFRADO_LLAVE'], 'CIFRADO_LLAVE=ficticia-conservar\n'],
  ['archivo existente sin llave', [], 'MYSQL_PASSWORD=ficticia-conservar\n'],
]) {
  test('preparador bloquea ' + nombre + ' antes de escribir o llamar a Docker', { skip: process.platform !== 'win32' }, t => {
    const raiz = fs.mkdtempSync(path.join(os.tmpdir(), 'tb-guarda-'));
    const archivo = path.join(raiz, '.env');
    fs.writeFileSync(archivo, contenido);
    fs.copyFileSync(path.resolve(__dirname, '../preparar-env.ps1'), path.join(raiz, 'preparar-env.ps1'));
    t.after(() => {
      assert.equal(path.dirname(raiz), os.tmpdir());
      assert.ok(path.basename(raiz).startsWith('tb-guarda-'));
      fs.rmSync(raiz, { recursive: true, force: true });
    });
    const r = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(raiz, 'preparar-env.ps1'), ...args], { encoding: 'utf8', timeout: 15000, windowsHide: true });
    assert.equal(r.status, 1);
    assert.equal(fs.readFileSync(archivo, 'utf8'), contenido);
    assert.ok(!r.stdout.includes('ficticia-conservar'));
    assert.ok(!r.stderr.includes('ficticia-conservar'));
    // No hay plantilla: llegar a generar o modificar el entorno haria fallar el caso.
    assert.ok(!fs.existsSync(path.join(raiz, '.env.example')));
  });
}
