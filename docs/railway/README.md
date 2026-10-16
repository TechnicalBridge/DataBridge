# DataBridge en Railway

Cómo desplegar DataBridge en [Railway](https://railway.com), servicio por servicio. El repositorio ya está
preparado; lo que falta es crear el proyecto allá y cargar las variables de esta guía.

**Antes de empezar, hay que tomar las decisiones de #173**: el plan y el correo, quién monta el proyecto, si
van los datos de la demo y cuándo está encendido. Esta guía cubre todas las opciones, y marca dónde cambia
algo según lo que se elija. La tarea es #172.

## Cómo queda

| Servicio en Railway | Carpeta | Dirección pública | Puerto |
| --- | --- | --- | --- |
| `portal` | `/frontend` | **Sí, la única** | 8080 |
| `gateway` | `/gateway` | No | 8082 |
| `ms-auth` | `/ms-auth` | No | 8081 |
| `ms-debt` | `/ms-debt` | No | 8083 |
| `ms-payments` | `/ms-payments` | No | 8084 |
| `ms-ai` | `/ms-ai` | No | 8085 |
| `MySQL` | La plantilla de Railway | No | 3306 |

- **El visitante solo llega al portal.** nginx le pasa `/api` al gateway, y el gateway a cada servicio, todo
  por la red privada de Railway (`<servicio>.railway.internal`).
- **No hay RabbitMQ.** Sin `EVENTS_RABBIT`, el aviso de un pago va de ms-payments a ms-debt por HTTP, con sus
  reintentos.
- **No hay buzón de prueba.** Los correos salen de verdad, por la API de Brevo (paso 5).
- **Los nombres de los servicios importan.** Las variables de abajo se refieren a ellos (`${{ms-auth...}}`):
  cada servicio tiene que llamarse exactamente como dice la primera columna.

## 1. El proyecto y la base

1. En Railway, un proyecto nuevo, vacío.
2. **+ New → Database → MySQL.** Que quede con el nombre `MySQL`. Trae su propio volumen: los datos
   sobreviven a los reinicios.
3. En el servicio `MySQL`, **Settings → Source**: que la imagen sea `mysql:8.4`, la misma versión que se usa en
   local. Si la plantilla trae otra, se cambia ahí antes del primer despliegue.

Las tres bases (`tb_auth`, `tb_debt`, `tb_payments`) las crea cada servicio la primera vez que se conecta, y
Flyway les pone sus tablas. No hay que correr nada a mano.

## 2. Las claves compartidas

En **Project Settings → Shared Variables**, cuatro variables:

| Variable | La usan |
| --- | --- |
| `JWT_SECRET` | ms-auth, ms-debt y ms-payments |
| `INTERNAL_KEY` | ms-auth, ms-debt y ms-payments |
| `WEBHOOK_SECRET` | ms-payments |
| `CIFRADO_LLAVE` | ms-debt |

**Tienen que ser nuevas**, nunca las del `.env` de un equipo. Cada una se genera en PowerShell con el mismo
generador que usa `preparar-env.ps1`, y se copia directo a Railway:

```powershell
$b = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Convert]::ToBase64String($b).TrimEnd('=').Replace('+', '-').Replace('/', '_') | Set-Clipboard
```

Queda en el portapapeles sin mostrarse en la pantalla. Se corre una vez por cada clave.

## 3. Los servicios

Para cada uno, **+ New → GitHub Repo → TechnicalBridge/TB_web**, y en su **Settings**:

- **Service Name:** el de la tabla de arriba.
- **Root Directory:** su carpeta (`/ms-auth`, `/frontend`…).
- **Config-as-code → Railway Config File:** `/<carpeta>/railway.json` (por ejemplo, `/ms-auth/railway.json`).
  Ahí ya están el Dockerfile, el chequeo de salud y que solo se vuelva a desplegar cuando cambia su carpeta.

Y en **Variables → Raw Editor**, las suyas. Lo que va entre `${{ }}` lo completa Railway solo.

### `ms-auth`

```
PORT=8081
SERVER_ADDRESS=::
JAVA_TOOL_OPTIONS=-XX:MaxRAM=640m -XX:MaxRAMPercentage=75
DB_HOST=${{MySQL.MYSQLHOST}}
DB_PORT=${{MySQL.MYSQLPORT}}
DB_USER=${{MySQL.MYSQLUSER}}
DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}
JWT_SECRET=${{shared.JWT_SECRET}}
INTERNAL_KEY=${{shared.INTERNAL_KEY}}
DEBT_URI=http://${{ms-debt.RAILWAY_PRIVATE_DOMAIN}}:8083
PUBLIC_URL=https://${{portal.RAILWAY_PUBLIC_DOMAIN}}
COOKIE_SECURE=true
ADMIN_CORREO=
```

`ADMIN_CORREO` es el correo del primer admin de DataBridge. Las variables del correo van en el paso 5, en
este mismo servicio.

### `ms-debt`

```
PORT=8083
SERVER_ADDRESS=::
JAVA_TOOL_OPTIONS=-XX:MaxRAM=640m -XX:MaxRAMPercentage=75
DB_HOST=${{MySQL.MYSQLHOST}}
DB_PORT=${{MySQL.MYSQLPORT}}
DB_USER=${{MySQL.MYSQLUSER}}
DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}
JWT_SECRET=${{shared.JWT_SECRET}}
INTERNAL_KEY=${{shared.INTERNAL_KEY}}
CIFRADO_LLAVE=${{shared.CIFRADO_LLAVE}}
AUTH_URI=http://${{ms-auth.RAILWAY_PRIVATE_DOMAIN}}:8081
DEMO_DATOS=true
CONTACTO_SIN_HORARIO=true
CONTACTO_SIN_LIMITE=true
```

- **`DEMO_DATOS`:** `true` con los datos de la demo, `false` sin ellos (#173, pregunta 3).
- **`CONTACTO_SIN_HORARIO` y `CONTACTO_SIN_LIMITE`:** `true` mientras sea una demo, para pedir códigos con
  *Reenviar código* a cualquier hora y cuantas veces haga falta. **Cuando DataBridge salga a cobrar de
  verdad, van en `false`**: la ley pide de lunes a sábado, de 8:00 a 20:00, y dos correos por semana.

### `ms-payments`

```
PORT=8084
SERVER_ADDRESS=::
JAVA_TOOL_OPTIONS=-XX:MaxRAM=640m -XX:MaxRAMPercentage=75
DB_HOST=${{MySQL.MYSQLHOST}}
DB_PORT=${{MySQL.MYSQLPORT}}
DB_USER=${{MySQL.MYSQLUSER}}
DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}
JWT_SECRET=${{shared.JWT_SECRET}}
INTERNAL_KEY=${{shared.INTERNAL_KEY}}
WEBHOOK_SECRET=${{shared.WEBHOOK_SECRET}}
DEBT_URI=http://${{ms-debt.RAILWAY_PRIVATE_DOMAIN}}:8083
PUBLIC_URL=https://${{portal.RAILWAY_PUBLIC_DOMAIN}}
KHIPU_URL_AVISOS=https://${{portal.RAILWAY_PUBLIC_DOMAIN}}/api/payments/public/khipu/aviso
KHIPU_LLAVE=
KHIPU_SECRETO=
MERCADOPAGO_PUBLIC_KEY=
MERCADOPAGO_ACCESS_TOKEN=
```

Las de Khipu y Mercado Pago son las credenciales de prueba de cada pasarela. Sin ellas, esa pasarela queda
simulada. Webpay no necesita nada: usa las credenciales públicas de integración de Transbank. Con una
dirección pública, Khipu por fin puede avisar cada pago (`KHIPU_URL_AVISOS`), en vez de esperar a la consulta
periódica.

### `ms-ai`

```
PORT=8085
SERVER_ADDRESS=::
JAVA_TOOL_OPTIONS=-XX:MaxRAM=640m -XX:MaxRAMPercentage=75
DEBT_URI=http://${{ms-debt.RAILWAY_PRIVATE_DOMAIN}}:8083
```

Con `XAI_API_KEY` responde con el LLM; sin ella, con reglas.

### `gateway`

```
PORT=8082
SERVER_ADDRESS=::
JAVA_TOOL_OPTIONS=-XX:MaxRAM=512m -XX:MaxRAMPercentage=75
AUTH_URI=http://${{ms-auth.RAILWAY_PRIVATE_DOMAIN}}:8081
DEBT_URI=http://${{ms-debt.RAILWAY_PRIVATE_DOMAIN}}:8083
PAYMENTS_URI=http://${{ms-payments.RAILWAY_PRIVATE_DOMAIN}}:8084
AI_URI=http://${{ms-ai.RAILWAY_PRIVATE_DOMAIN}}:8085
CORS_ORIGINS=https://${{portal.RAILWAY_PUBLIC_DOMAIN}}
```

### `portal`

```
PORT=8080
GATEWAY_HOST=${{gateway.RAILWAY_PRIVATE_DOMAIN}}
GATEWAY_PORT=8082
PROXY_DE_ENTRADA=0.0.0.0/0
```

Después, en **Settings → Networking → Generate Domain**, con el puerto 8080. Es la única dirección pública.

**Por qué cada variable que no es obvia:**

- **`SERVER_ADDRESS=::`** hace que el servicio escuche también en IPv6. En los ambientes de Railway creados
  antes del 16 de octubre de 2025, la red privada es solo IPv6, y un servicio que escucha en IPv4 no se
  alcanza.
- **`JAVA_TOOL_OPTIONS`** le pone techo a la memoria de cada JVM. En Docker Compose ese techo lo pone el
  límite del contenedor; en Railway no hay uno tan bajo, y Java crecería hasta donde lo dejen, que se cobra.
- **`PROXY_DE_ENTRADA=0.0.0.0/0`** hace que nginx le crea al proxy de Railway la IP del visitante
  (`X-Real-IP`). Sin eso, el gateway vería a todos los visitantes como uno solo, el proxy, y el límite de diez
  ingresos por minuto se lo repartirían entre todos.

## 4. El orden del primer despliegue

1. `MySQL`.
2. `ms-auth`, `ms-debt` y `ms-payments`. Cada uno crea su base y la migra al arrancar: el primero tarda un
   par de minutos.
3. `ms-ai`, después `gateway` y al final `portal`.

Un servicio aparece activo cuando su chequeo de salud responde. En local, Docker Compose se encarga del
orden; en Railway, si uno parte antes que otro del que depende, se reinicia solo hasta que el otro esté.

## 5. El correo

Por la **API de Brevo**, como se decidió en #173: va por HTTPS, que Railway no bloquea, y es gratis hasta 300
correos al día. La cuenta de Brevo y su clave se preparan como dice el README (*Para mandar correos de
verdad*, con Brevo). En `ms-auth`:

```
BREVO_API_KEY=<la clave de API de Brevo>
MAIL_FROM=tucuenta@gmail.com
MAIL_FROM_NAME=DataBridge
CORREO_SOLO_A=tucuenta@gmail.com
CORREO_DESVIAR_A=tucuenta@gmail.com
```

- **`MAIL_FROM`** es el Gmail del equipo, verificado como remitente en Brevo.
- **En Brevo, sin bloqueo por IP** (*Security → Authorised IPs*): en el plan Hobby, la IP con que Railway
  sale a internet cambia, y Brevo rechazaría cada correo con un `401`.
- **`CORREO_SOLO_A`** son los correos de prueba a los que sí se les escribe directo, separados por coma, y
  **`CORREO_DESVIAR_A`**, adonde va todo lo demás. Con los datos de la demo no son opcionales: los deudores de
  ejemplo tienen correos inventados. Cuando DataBridge salga a cobrar de verdad, se dejan vacías.

El SMTP (Gmail) no sirve aquí: Railway lo bloquea en los planes Free, Trial y Hobby.

## 6. Comprobar que funciona

- El portal abre en `https://<dominio>.up.railway.app`.
- El correo de prueba sale (`POST /internal/correo/prueba`, desde la consola de `ms-auth` en Railway), y un
  deudor entra con el código que le llega.
- El admin entra a `/admin` con un enlace a `ADMIN_CORREO`.
- Un pago en el ambiente de integración de una pasarela llega a abonarse en la deuda.

## 7. Apagarlo

Railway no cobra un servicio detenido, y la base queda guardada en su volumen. Si DataBridge solo se enciende
para presentar (#173, pregunta 4), se detienen todos menos `MySQL` desde el panel, y se vuelven a encender
unos minutos antes. Mientras está apagado no corre ninguna tarea programada: la campaña, los recordatorios ni
la UF del día.

## Qué se cambió en el repositorio para esto

Nada cambia en local: Docker Compose sigue funcionando igual.

| Qué | Por qué |
| --- | --- |
| Un `railway.json` en cada carpeta | El Dockerfile, el chequeo de salud y que cada servicio se despliegue solo cuando cambia lo suyo |
| Los Dockerfiles de Java ya no montan un caché de Maven | Railway rechaza esos montajes si no llevan el id de cada servicio suyo. Las dependencias quedan en su propia capa |
| `frontend/nginx.conf.template` | El gateway, el DNS y el proxy de entrada salen de variables; sus valores por omisión son los de Docker Compose |
| `createDatabaseIfNotExist=true` en la conexión de cada servicio | En local las bases las crea `db/init`, que fuera de Docker Compose no corre |
| El gateway escucha en `PORT`, si lo hay | Es el puerto al que Railway le manda su chequeo de salud |
| El gateway confía en las redes privadas de IPv6 | Por ahí le llega el portal en la red privada de Railway |

**Fuentes:** [el SMTP de salida](https://docs.railway.com/networking/outbound-networking),
[la red privada](https://docs.railway.com/networking/private-networking/how-it-works),
[config as code](https://docs.railway.com/reference/config-as-code),
[MySQL](https://docs.railway.com/databases/mysql) y [precios](https://railway.com/pricing).
