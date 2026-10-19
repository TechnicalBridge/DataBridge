# Secretos y rotación del cifrado

Las credenciales reales van en archivos privados de la instalación, nunca en el código, ejemplos, capturas,
issues o registros. `.env` y `.env.*` están ignorados; `.env.example` es la excepción versionada.
Ignorar archivos no elimina datos de commits anteriores.

## Rotar CIFRADO_LLAVE sin perder suscripciones

La llave protege `tb_debt.subscriptions.secret`, que ms-debt lee para firmar eventos. Se mantiene el formato
`enc:v1:` con AES-256-GCM: el receptor conserva su secreto y no tiene que suscribirse nuevamente.
**Cambiar solo la llave del archivo deja ilegibles los registros existentes.**

`scripts/rotar-cifrado.cjs` es mantenimiento local, fuera de Spring: no arranca tareas, consumidores, correo
ni cobros. Solo usa el MySQL y ms-debt existentes del proyecto `tb_web`, con MySQL publicado en este equipo.
No crea contenedores de prueba ni otro proyecto de Compose.

Requisitos: Node 22 o posterior, JDK 25, Docker iniciado y una ventana de mantenimiento sin ediciones paralelas
del `.env`. MySQL y ms-debt deben estar iniciados y coincidir con el archivo indicado. `subscriptions` debe
usar InnoDB: no se rota si falta su metadata o el motor no permite rollback. Los respaldos quedan fuera de
cualquier repositorio, incluso cuando la ruta pasa por un enlace; por defecto, en `respaldos`, junto a la
carpeta de la instalación.

Desde la raíz del repositorio, en PowerShell 5.1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
$env:PATH = $env:JAVA_HOME + '\bin;' + $env:PATH
.\mvnw.cmd -f ms-debt\pom.xml clean test dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
if ($LASTEXITCODE -ne 0) { throw 'No continuar: falló la compilación o alguna prueba.' }
node scripts\rotar-cifrado.cjs --verificar
```

La verificación descifra y comprueba todos los registros dentro de una transacción que revierte al terminar.
No escribe archivos o datos ni detiene servicios. La salida informa solo cantidad y resultado. Rechaza valores
vacíos, en claro o corruptos, que deben revisarse antes de rotar.

**Solo con autorización para la base, el archivo y la pausa de ms-debt:**

```powershell
node scripts\rotar-cifrado.cjs --aplicar
```

Para un `.env` en otra carpeta se indican rutas, nunca valores:

```powershell
node scripts\rotar-cifrado.cjs --verificar --entorno 'C:\ruta\TB_web\.env' --respaldos 'C:\ruta\respaldos'
```

Al aplicar:

1. Comprueba acceso y descifrado, prepara una llave aleatoria y bloquea rotaciones concurrentes. Después de
   tomar el bloqueo revalida el archivo y las instancias de MySQL y ms-debt; si cambiaron, no detiene servicios.
2. Guarda `.env` y `.env.propuesto` en un respaldo privado; detiene solo ms-debt y respalda `tb_debt` con `mysqldump`.
3. Bloquea y comprueba todos los registros antes de escribir. Recifra, verifica lo guardado y confirma una única
   transacción. Si falla antes de confirmar, revierte las escrituras.
4. Reemplaza el `.env` sin cambiar otras variables. Recrea solo ms-debt, sin dependencias, sin construir ni descargar
   imágenes: fija el ID de su imagen original y conserva sus variables, excepto la llave.
5. Espera la salud, comprueba las variables y verifica el descifrado con la nueva llave. No imprime secretos ni
   reinicia ms-payments u otros servicios.

Después se comprueba el acceso por el gateway, sin imprimir el documento completo de Swagger:

```powershell
$api = Invoke-RestMethod 'http://localhost:8080/v3/api-docs/ms-debt/portal'
($api.paths.PSObject.Properties | Measure-Object).Count
```

Si Swagger está apagado, usar el recorrido autenticado habitual sin enviar correos ni pagos reales. Si el gateway
conserva la dirección anterior, revisar su resolución antes de autorizar su reinicio: la herramienta no lo reinicia.

## Recuperación

El respaldo contiene `.env`, `.env.propuesto`, `tb_debt.sql`, `estado.json` y la configuración temporal de Compose.
**Tienen secretos y datos: no se suben ni se adjuntan como evidencia pública.** Proteger sus permisos y conservar
la llave antigua junto al respaldo correspondiente, fuera del repositorio.

El diario distingue fase y última fase segura: `preparando`, `respaldado`, `base-migrada`, `entorno-actualizado`
y `completa`. Un fallo después de intentar migrar deja `pendiente-de-recuperacion`, el servicio detenido y un
archivo `.lock` para impedir otra rotación.

- Si falla el respaldo antes de intentar migrar, no cambia la base ni el archivo y se intenta iniciar la misma
  instancia detenida. Comprobar igualmente su salud.
- Si falla durante el commit o se pierde la conexión, no asumir que hubo rollback. Mantener ms-debt detenido
  y comprobar qué llave lee los registros antes de elegir el archivo que se aplicará.
- Si falla el cierre de la conexión después del commit, tampoco se informa un rollback seguro ni se imprime
  primero un resultado exitoso. Se requiere comprobar el estado antes de iniciar el servicio.
- Si la última fase segura es `base-migrada` o `entorno-actualizado`, el respaldo conserva la propuesta para los
  datos nuevos. No recuperar solo el `.env` antiguo sobre una base recifrada.
- Recuperar la propuesta y recrear el servicio exige revisar diario, acceso e imagen original. No borrar el bloqueo
  ni reejecutar `--aplicar` sin resolver el estado pendiente.
- Restaurar toda la base es una operación separada: requiere autorización, detener escrituras y comprobar los
  cambios posteriores al respaldo. No se automatiza ni se promete una recuperación sin revisión.

`preparar-env.ps1` rechaza `-TambienCifrado` y `-Cambiar CIFRADO_LLAVE`: reconocer una advertencia ya no permite
saltar la migración. La operación no rota contraseñas de MySQL, claves de pasarelas ni otros secretos.

### Ensayo local de recuperación

El 19 de octubre de 2026 se restauró el respaldo completo previo a la rotación en un esquema temporal del
MySQL existente: 16 tablas, 2 vistas y 1.635 filas, incluida una suscripción cifrada. Se usó un usuario temporal
sin acceso a las bases activas; los definidores de las dos vistas se adaptaron a ese usuario, sin alterar datos.

La verificación no escribió datos. Después se recifró solo la copia, conservando el secreto y las otras tablas,
y se restauró nuevamente el respaldo. Coincidieron todos los datos con la primera restauración y la llave
anterior volvió a descifrar la suscripción. El esquema y el usuario temporales se eliminaron al terminar.
El `.env`, las suscripciones activas y las nueve instancias de Docker permanecieron sin cambios.

Este ensayo comprueba ese respaldo y la migración en copia, no una recuperación de producción ni todos los
cortes posibles entre MySQL, el archivo y Compose. Una restauración de la instalación activa sigue necesitando
autorización y coordinación; no se ejecutó en este ensayo. Tampoco sustituye comprobar permisos y definidores
de vistas en el servidor de destino.

## Prevención de secretos nuevos

El workflow `Prevencion de secretos` usa Gitleaks 8.30.1 con SHA-256 verificado, reglas predeterminadas y reglas
adicionales para tokens privados de Mercado Pago, Render, secretos internos literales y valores por omisión.
Comprueba los commits nuevos del PR o del push a main. **No limpia ni certifica el historial anterior.**

Las excepciones son valores públicos exactos: la llave oficial de integración de Transbank y las huellas de
valores antiguos. No se omiten pruebas, hashes arbitrarios ni comentarios `gitleaks:allow`. Solo se muestran
regla, ruta y línea, nunca los registros ni los campos Secret, Match o autorías.

Con el binario oficial de Gitleaks en PATH o indicado por `GITLEAKS`, se revisa el índice preparado para el commit:

```powershell
node scripts\verificar-secretos.cjs --staged
```

No revisa archivos sin agregar. Para activar el hook versionado, después de integrar la rama y tener `.githooks`
y `scripts` en los clones correspondientes:

```powershell
git config --local core.hooksPath .githooks
```

No se activa automáticamente en un repositorio con worktrees en otras ramas. El hook bloquea si falta el detector.
Después del merge se puede incorporar el check a la protección de main, que no se modifica automáticamente.
Las pruebas generan datos ficticios en carpetas temporales y verifican positivos y excepciones concretas.
La prevención no reemplaza la revisión ni la rotación de credenciales publicadas.
