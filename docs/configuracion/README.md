# Configuracion por instalacion

Para replicar las cuentas, cartera y pagos de una instalación existente, usar la
[guía de réplica privada](otro-pc.md). Esa operación conserva las claves del respaldo y no genera
otras sobre los datos cifrados. Lo que sigue describe principalmente una instalación nueva.

Hay tres responsabilidades distintas:

| Archivo | Responsabilidad | Se comparte |
| --- | --- | --- |
| `.env.example` | Contrato publico de variables, sin secretos privados | En Git |
| `.env` | Configuracion efectiva de un PC o servidor | No se copia completo entre instalaciones con bases propias |
| Archivo privado de proveedores | Correo, cuentas de prueba, pasarelas y Banco Central elegidos | Solo por un canal privado y a quienes lo necesiten |

Un inventario personal de credenciales no es un formato de importacion. Se revisan las cuentas vigentes y se
extraen solo sus variables al perfil privado; el preparador nunca busca ni interpreta documentos personales.
La plantilla publica del perfil es [`proveedores.env.example`](proveedores.env.example).

## Instalacion nueva

Docker Desktop debe estar iniciado. Desde la raiz de TB_web, en PowerShell 5.1:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\preparar-env.ps1
```

Genera siete secretos internos independientes de los otros equipos. Si encuentra contenedores o el volumen
anterior de `tb_web`, no inventa secretos que falten: pide recuperar el `.env` correcto. No inicia ni borra
contenedores. Si Docker no responde, se detiene antes de escribir.

Para incorporar proveedores se usa un archivo privado fuera del repositorio, con solo variables admitidas:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\preparar-env.ps1 -Proveedores ..\.env.proveedores
```

El perfil no admite claves de MySQL, RabbitMQ, JWT, cifrado ni comunicacion interna. No pisa valores propios
del `.env`: reemplazarlos requiere revisar las cuentas y agregar expresamente `-ReemplazarProveedores`.
Con correo real y datos de demo, `CORREO_SOLO_A` o `CORREO_DESVIAR_A` es obligatorio. Preparar el archivo no
comprueba que Brevo, SMTP o una pasarela acepten sus credenciales, ni envia correos o inicia pagos.

El formato es `NOMBRE=valor`, con comentarios en su propia linea. Se admiten comillas simples para valores
literales; no se ejecuta codigo ni se expande una referencia a otra variable. Se rechazan duplicados, comillas
incompletas y formatos ambiguos. Las claves generadas no necesitan comillas ni escapes. Una variable de la
terminal que contradiga un secreto interno o `MYSQL_USER` detiene la preparacion en vez de sobrescribirlo
silenciosamente. Esta verificacion cubre el flujo Docker; el arranque Maven tiene su propia configuracion.
El usuario efectivo de MySQL es `tbridge`, tal como lo requieren los permisos del inicializador. Se distingue
entre mayusculas y minusculas; una variable ausente o vacia conserva el valor por omision de Compose.

## Cuenta Del Banco Central

La cuenta de la Base de Datos Estadisticos (BDE) se incorpora al perfil privado con `BCENTRAL_USER` y
`BCENTRAL_PASS`, siempre juntos. No es la contrasena de Gmail ni la llave de Brevo: deben ser los datos
registrados en Banco Central. El preparador no los genera, no los publica y rechaza un perfil incompleto,
incluso si el `.env` ya tiene otra cuenta, para no mezclar credenciales de cuentas distintas.

Un solo par exterior de comillas codifica un valor literal. Otro par dentro del valor se considera ambiguo y
se rechaza sin corregirlo automáticamente. Si las comillas forman realmente parte de la contraseña, se puede
confirmar con `-PermitirComillasBancoCentral`; el correo nunca admite ese formato. Esto conserva la contraseña
exacta, no interpreta código ni quita sus caracteres.

El Compose ya transmite ambas variables a ms-payments. Preparar el `.env` no las aplica a un contenedor
existente ni comprueba que Banco Central las acepte. `-Verificar` compara lo configurado con el entorno
de ms-payments cuando hay una cuenta en el archivo; no llama al proveedor. Una diferencia exige revisar
y aplicar la configuracion por separado, con autorizacion.

Al arrancar con la cuenta configurada, ms-payments consulta la serie de UF y guarda los valores publicados;
tambien tiene una carga programada. Por eso no se reinicia el servicio como parte de preparar o verificar
el archivo. Sin cuenta se mantiene la carga manual de UF; no se cambia esa logica.

## Verificar despues de arrancar

```powershell
docker compose --profile app up -d --build --wait
powershell -NoProfile -ExecutionPolicy Bypass -File .\preparar-env.ps1 -Verificar
```

`-Verificar` no crea respaldos ni modifica `.env`, usuarios, datos o contenedores. Comprueba que los secretos
esten presentes, la longitud minima del JWT, el acceso root y el acceso de la aplicacion a las tres bases.
Compara tambien las claves internas de los servicios ya creados con las del archivo. No significa que los
proveedores externos funcionen ni que se hayan ejecutado pruebas integrales del portal.

Si hay variables `LLM_*`, `OLLAMA_*`, `GROQ_*` o `GEMINI_*` configuradas y existe el contenedor ms-ai, tambien
comprueba que las reciba con los mismos valores. Un error aqui no prueba un fallo de la llave ante el proveedor:
indica una diferencia entre el archivo y el proceso desplegado. No se imprime el valor ni se llama al LLM.

Para comprobar un archivo existente con una copia mas reciente del script, sin mover las claves:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\preparar-env.ps1 -Verificar -ArchivoParaVerificar ..\otra-copia\TB_web\.env
```

`-ArchivoParaVerificar` no se admite en un modo que escriba. En todos los casos el proyecto Docker es el
existente `tb_web`; no se crea otro proyecto para probar.

## Ordenar Un Archivo Existente

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\preparar-env.ps1 -Ordenar
```

Agrupa secretos internos, MySQL, RabbitMQ, direcciones, acceso, correo, pasarelas, cobranza e IA. Conserva todas
las variables, sus valores literales y los comentarios propios; los campos desconocidos quedan en un grupo
final. No completa variables de la plantilla, no genera ni importa llaves, no llama a Docker y no cambia
contenedores. Se respalda el original antes de escribir y una segunda ejecucion no duplica encabezados ni
respaldos. Se normalizan espacios en blanco entre bloques, no los valores de las asignaciones.

Para ordenar el `.env` operativo con una copia nueva del preparador, sin mover secretos entre repositorios:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\preparar-env.ps1 -Ordenar -ArchivoParaOrdenar ..\otra-copia\TB_web\.env
```

Este modo es excluyente con verificacion, importacion y rotacion. Un archivo ausente o con variables duplicadas
se rechaza antes de escribir. El respaldo queda junto a la instalacion del archivo elegido, en su carpeta
hermana `respaldos`, y el reemplazo atomico se hace en el mismo volumen del destino.

## Variables De IA

El contrato de la cascada del [PR #214](https://github.com/TechnicalBridge/TB_web/pull/214) incluye trece campos:

- `LLM_ORDEN`, `LLM_ENFRIAMIENTO_MINUTOS`.
- `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `OLLAMA_TIMEOUT`.
- `GROQ_API_KEY`, `GROQ_BASE_URL`, `GROQ_MODEL`, `GROQ_TIMEOUT`.
- `GEMINI_API_KEY`, `GEMINI_BASE_URL`, `GEMINI_MODEL`, `GEMINI_TIMEOUT`.

El preparador los conserva y admite en un perfil privado explicitamente elegido. **No genera llaves de Groq o
Gemini, no elige ni verifica modelos y no incorpora codigo de ese PR.** Las plantillas los muestran comentados:
en la base de esta correccion, main todavia configura ms-ai con `XAI_*`. Agregar una llave al archivo no basta
si el Compose no la transmite o el servicio no la consume. No se debe copiar una llave de Groq/Gemini a
`XAI_API_KEY` para intentar activar otra integracion.

Ollama depende del equipo donde este instalado: su direccion y modelo no se suponen portables por compartir
un archivo. Un perfil de correo, Khipu y Banco Central no se amplia automaticamente con llaves de IA. Los valores nuevos
del `.env` operativo se preservan sin ejecutar peticiones, enviar deudas al proveedor ni activar consumo.

## Desajustes Y Recuperacion

MySQL aplica `MYSQL_PASSWORD` y `MYSQL_ROOT_PASSWORD` al inicializar la base. Volver a construir las imagenes
o reemplazar `.env` no cambia las contrasenas de una base persistida. Dentro de Docker, Compose toma
`MYSQL_PASSWORD` y lo entrega como `DB_PASSWORD` a los servicios: no hacen falta dos claves distintas.

| Diagnostico | Accion |
| --- | --- |
| Faltan secretos pero existe infraestructura | Recuperar el `.env` de esa instalacion desde su respaldo; no copiar otro ni borrar volumenes |
| MySQL rechaza root | Recuperar la credencial administrativa correcta antes de planificar cambios; el script no intenta saltarse la autenticacion |
| Root funciona y la aplicacion no entra | Revisar la cuenta y las tres bases; si corresponde, autorizar una rotacion expresa de `MYSQL_PASSWORD` |
| MySQL acepta las claves pero un servicio conserva otras | Recrear ese servicio desde la configuracion correcta; no cambiar la base para ajustarla a un contenedor viejo |
| MySQL figura saludable | El healthcheck ahora ejecuta una consulta autenticada y comprueba las tres bases; `mysqladmin ping` solo acreditaba que el servidor respondia |

No usar `docker compose down -v` para resolver estas situaciones: destruye los datos. Antes de cambiar un
archivo existente, el preparador lo copia byte a byte a `..\respaldos\entorno-<fecha>-<id>\.env`, fuera del
repositorio. Cada escritura se reemplaza atomicamente. Los respaldos y `.env.propuesto` contienen secretos;
se protegen igual que el original y nunca se suben a Git. Si una escritura falla despues de una rotacion,
el candidato permite recuperar la clave propuesta. Un fallo devuelve codigo 1, no un mensaje de exito.

Una rotacion no se hace para instalar otro PC. Solo ante una necesidad confirmada y con autorizacion:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\preparar-env.ps1 -Cambiar MYSQL_PASSWORD
```

El script autentica root y cambia tambien la cuenta MySQL antes de guardar esa clave. Si falla, retorna error.
Las rotaciones SQL sucesivas se persisten por separado para no perder un cambio ya aplicado si falla el
siguiente. `-Renovar` sigue seleccionando valores publicos antiguos por su huella, pero se detiene si requiere
una operacion especial: RabbitMQ necesita cambiar la cuenta tambien en el broker, y `CIFRADO_LLAVE` requiere
una migración con respaldo y recifrado (#232). `-TambienCifrado` y `-Cambiar CIFRADO_LLAVE` se rechazan;
ninguna confirmación permite reemplazarla sin migrar. No se cambian silenciosamente esos secretos.

Despues de una rotacion autorizada se recrean los servicios afectados y se repite `-Verificar`. Si cambia
`MYSQL_PASSWORD`, se recrea tambien el contenedor mysql, conservando su volumen: el nuevo healthcheck lee
esa variable de su entorno. Un simple `restart` no incorpora el `.env` actualizado. El preparador no
despliega ni recrea contenedores automaticamente. La verificacion no autentica contra RabbitMQ ni proveedores
externos: esas comprobaciones se coordinan aparte.

## Pruebas De La Herramienta

```powershell
node --test pruebas\entorno.test.cjs
```

Se ejecuta PowerShell 5.1 con Docker simulado, sin llamadas al motor, bases, correos o pasarelas reales. Los
casos cubren preparacion nueva, conservacion de secretos, respaldos, fallos de autenticacion/rotacion,
infraestructura previa, duplicados, importacion restringida y verificacion sin escrituras. El workflow
`Entorno Windows` repite estos casos en los PR que cambien la herramienta; disponer del workflow no demuestra
que la CI ya se haya ejecutado.
