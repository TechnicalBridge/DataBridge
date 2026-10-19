# Llevar Esta Instalación A Otro PC

La rama `chore/237-integracion-portable` reúne el código, no las cuentas ni la base. Tiene dos caminos distintos:

| Necesidad | Qué se lleva | Secretos internos |
| --- | --- | --- |
| Instalar desde cero | Rama y proveedores elegidos expresamente | `preparar-env.ps1` genera los del nuevo PC |
| Recuperar las mismas cuentas, campañas y pagos | Rama exacta y paquete privado con `.env`, `bases.sql` y `manifiesto.json` | Se conservan los del respaldo; no se regeneran sobre la base restaurada |

No mezclar estos caminos. Una demo nueva usa su cuenta `example.com`; no sustituye ni renombra el personal
existente. Una réplica conserva las cuentas que realmente figuren en su respaldo.

## Réplica De La Instalación

El paquete privado contiene datos y credenciales reales. Se transporta por un medio privado, nunca por GitHub,
un issue, un PR, un chat ni un enlace público. Tiene que quedar fuera del repositorio en el otro PC.
La integridad se comprueba con SHA-256; esto detecta corrupción, no reemplaza confiar en el origen del paquete.

**Estos comandos son exclusivamente para EL OTRO PC.** No ejecutar una restauración sobre el equipo de origen.
Se necesitan Git, Docker Desktop iniciado, PowerShell y Node 22 o posterior para la herramienta de recuperación.

En el clon de TB_web del otro PC:

```powershell
git fetch origin
git switch --track origin/chore/237-integracion-portable
# Si la rama local ya existe: git switch chore/237-integracion-portable
node scripts\restaurar-instalacion.cjs --paquete 'C:\ruta-privada\replica-tbweb'
```

Por omisión solo valida el paquete: no inicia Docker, no escribe `.env` ni restaura bases.
La rama y el commit deben coincidir con el manifiesto al aplicar. La herramienta **se niega** a aplicar en el
equipo de origen, a reemplazar un `.env` existente o a importar sobre bases con tablas. No borra volúmenes.

Si el otro PC ya tiene una instalación de prueba o datos propios, detenerse y decidir una recuperación
específica, con respaldo previo. No usar `down -v`, ni borrar el `.env` para saltarse la protección.

Solo en un destino nuevo y autorizado:

```powershell
node scripts\restaurar-instalacion.cjs --paquete 'C:\ruta-privada\replica-tbweb' --aplicar
```

La herramienta inicia únicamente MySQL, comprueba que las tres bases están vacías, conserva el `.env`
del respaldo y restaura el SQL. Verifica los totales y deja un estado privado; no inicia los demás servicios.
Si falla después de empezar, no se repite a ciegas: revisar `estado-restauracion.json` y planificar la recuperación.

Después de una restauración correcta, siempre EN EL OTRO PC, desde esa misma rama:

```powershell
docker compose -p tb_web --profile app up -d --build --wait
powershell -NoProfile -ExecutionPolicy Bypass -File .\preparar-env.ps1 -Verificar
```

Se construye todo desde la misma rama para que el portal y ms-auth compartan el contrato de acceso.
El portal queda en `http://localhost:8080` salvo otro `PORTAL_PORT`. Las credenciales y el contenido de la base
no se muestran como evidencia. `-Verificar` puede informar variables de un proveedor todavía no aplicadas:
no cambiarlas en la base para disimular un desajuste.

## Correo Real Y Códigos Solicitados

Mailpit queda fuera de Compose. Una réplica conserva el proveedor real y su desvío del respaldo; una instalación
nueva no recibe claves externas automáticamente. Con Brevo hay que autorizar la IP pública del nuevo equipo
en la cuenta correspondiente, y comprobar el remitente y el cupo de esa cuenta antes de probar la entrega.

Si el respaldo conserva campos `LLM_*`, `GROQ_*`, `GEMINI_*` u `OLLAMA_*` que esta rama todavía no consume,
`-Verificar` lo informa como error de configuración de IA; no significa que la copia de MySQL haya fallado.
No renombrar claves entre proveedores ni eliminar datos para obtener un resultado verde. Revisar el soporte
del PR #214 antes de declarar operativo un modelo externo; el asistente local conserva su camino sin LLM.

- Cargar, actualizar, reabrir o restaurar una cartera no emite códigos ni agenda invitaciones.
- No hay tareas periódicas de invitaciones, toques con código ni recordatorios de cuota.
- Se conserva **Enviar código**, **¿No te llegó?** y el reenvío manual autorizado por la empresa.
- Las operaciones explícitas de mantenimiento siguen pudiendo enviar correo: no ejecutarlas como prueba masiva.
- Una solicitud no demuestra entrega: el mensaje público es el mismo para una identidad inexistente.

El cambio de disparadores está en esta rama; no modifica el software que ya está corriendo en otro checkout.
Mantener dos instalaciones puede duplicar otras tareas de eventos o conciliación: elegir cuál operará antes
de usarlas simultáneamente con proveedores reales. No se clona una cola RabbitMQ con SQL; el respaldo preparado
se tomó con cero mensajes pendientes, dato registrado en su manifiesto.

## Desarrollo Con Maven Y Vite

JDK 25 para los servicios. La contraseña `MYSQL_PASSWORD` se reutiliza si falta `DB_PASSWORD`; una variable
`DB_PASSWORD` presente pero vacía es un error y no se reemplaza silenciosamente.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
$env:PATH = $env:JAVA_HOME + '\bin;' + $env:PATH
npm ci --prefix frontend
npm run dev --prefix frontend
```

Vite usa `http://127.0.0.1:PORTAL_PORT`, la entrada publicada de Docker que enruta al gateway interno.
`DEV_PROXY_TARGET` en el `.env` de TB_web permite otro destino HTTP/HTTPS sin credenciales en la URL.
No se publica el puerto interno 8082 del gateway solo para hacer funcionar Vite.

## Límites

Esta integración no incorpora cambios reservados de seguridad ni el PR #214 de IA. Conservar una variable
de un proveedor no implementa una integración ausente. No se renuevan claves ni se limpian commits antiguos.
No contiene bases, `.env` privados, los datos del equipo ni el paquete de réplica.
La restauración real sobre el otro PC requiere ejecutarse y verificarse allí; una prueba en copia no la reemplaza.
