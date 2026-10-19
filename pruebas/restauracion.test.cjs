const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { restaurar, opciones } = require('../scripts/restaurar-instalacion.cjs');

function escenario(t) {
  const raiz = fs.mkdtempSync(path.join(os.tmpdir(), 'tb-replica-'));
  const repo = path.join(raiz, 'TB_web'), paquete = path.join(raiz, 'privado');
  fs.mkdirSync(repo); fs.mkdirSync(paquete);
  const variables = ['MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'JWT_SECRET', 'INTERNAL_KEY', 'CIFRADO_LLAVE', 'WEBHOOK_SECRET', 'RABBIT_PASSWORD'];
  const contenido = Object.fromEntries(variables.map(n => [n, 'ficticia-' + n]));
  fs.writeFileSync(path.join(paquete, '.env'), Object.entries(contenido).map(([n,v])=>n+'='+v).join('\n'));
  fs.writeFileSync(path.join(paquete, 'bases.sql'), '-- SQL ficticio de prueba\n');
  const manifiesto = { version:1, bases:['tb_auth','tb_debt','tb_payments'], verificacionRestauracion:'correcta-en-copia-aislada', equipoOrigen:'equipo-origen-ficticio', rama:'chore/237-integracion-portable', commit:'a'.repeat(40), archivos:{}, conteosConsultadosDespuesDelSnapshot:{deudas:3} };
  for (const n of ['.env','bases.sql']) manifiesto.archivos[n]=crypto.createHash('sha256').update(fs.readFileSync(path.join(paquete,n))).digest('hex');
  fs.writeFileSync(path.join(paquete,'manifiesto.json'),JSON.stringify(manifiesto));
  const llamadas=[];
  const ejecutar=(cmd,args,extra)=>{
    llamadas.push({cmd,args,extra});
    if(cmd==='git')return args.includes('HEAD')?'a'.repeat(40):manifiesto.rama;
    if(extra?.input?.startsWith?.('SELECT'))return JSON.stringify(extra.input.includes('information_schema')?{n:0}:{deudas:3});
    return '';
  };
  t.after(()=>{assert.equal(path.dirname(raiz),os.tmpdir());assert.ok(path.basename(raiz).startsWith('tb-replica-'));fs.rmSync(raiz,{recursive:true,force:true});});
  return {repo,paquete,llamadas,ejecutar,o:{repo,paquete,equipo:'equipo-destino-ficticio',aplicar:true}};
}

test('la inspeccion por omision no llama a Docker ni escribe el destino',t=>{
  const e=escenario(t); const r=restaurar({...e.o,aplicar:false},e.ejecutar);
  assert.equal(r.aplicado,false);assert.equal(e.llamadas.length,0);assert.deepEqual(fs.readdirSync(e.repo),[]);
});
test('no restaura sobre el PC de origen',t=>{
  const e=escenario(t);assert.throws(()=>restaurar({...e.o,equipo:'equipo-origen-ficticio'},e.ejecutar),/equipo de origen/);assert.equal(e.llamadas.length,0);
});
test('no reemplaza un env ni una instalacion existente',t=>{
  const e=escenario(t);fs.writeFileSync(path.join(e.repo,'.env'),'original');assert.throws(()=>restaurar(e.o,e.ejecutar),/ya tiene/);assert.equal(fs.readFileSync(path.join(e.repo,'.env'),'utf8'),'original');assert.equal(e.llamadas.length,0);
});
test('un archivo alterado impide cualquier operacion',t=>{
  const e=escenario(t);fs.appendFileSync(path.join(e.paquete,'bases.sql'),'alterado');assert.throws(()=>restaurar(e.o,e.ejecutar),/integridad/);assert.equal(e.llamadas.length,0);
});
test('una base con tablas no se sobrescribe',t=>{
  const e=escenario(t);assert.throws(()=>restaurar(e.o,(cmd,args,extra)=>extra?.input?.includes?.('information_schema')?'{"n":1}':e.ejecutar(cmd,args,extra)),/no estan vacias/);assert.equal(fs.existsSync(path.join(e.repo,'.env')),false);
});
test('una aplicacion activa impide restaurar aunque las tablas esten vacias',t=>{
  const e=escenario(t);assert.throws(()=>restaurar(e.o,(cmd,args,extra)=>args[0]==='ps'?'ms-auth\n':e.ejecutar(cmd,args,extra)),/aplicacion activos/);assert.equal(fs.existsSync(path.join(e.repo,'.env')),false);
});
test('restaura solo con accion explicita y no inicia la aplicacion',t=>{
  const e=escenario(t);const r=restaurar(e.o,e.ejecutar);assert.equal(r.aplicado,true);assert.equal(r.serviciosDeAplicacionIniciados,false);
  assert.ok(e.llamadas.every(l=>!l.args.includes('down')&&!l.args.includes('-v')&&!l.args.includes('ms-auth')));
  assert.ok(e.llamadas.every(l=>l.args.every(a=>!String(a).includes('ficticia-'))));
  assert.equal(fs.readFileSync(path.join(e.repo,'.env'),'utf8'),fs.readFileSync(path.join(e.paquete,'.env'),'utf8'));
});
test('un fallo SQL conserva el estado pendiente y no borra bases',t=>{
  const e=escenario(t);assert.throws(()=>restaurar(e.o,(cmd,args,extra)=>{if(args.includes('--binary-mode=1'))throw new Error('fallo ficticio');return e.ejecutar(cmd,args,extra);}),/no se completo/);
  assert.equal(JSON.parse(fs.readFileSync(path.join(e.paquete,'estado-restauracion.json'))).fase,'pendiente-de-revision');
});
test('las opciones desconocidas se rechazan',()=>{assert.throws(()=>opciones([]));assert.throws(()=>opciones(['--borrar']));});
