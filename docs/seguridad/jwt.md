# Sesiones ES256 y rotacion de claves

Solo **ms-auth** firma sesiones con ES256/P-256 (secp256r1). Debt y payments usan
Spring Security OAuth2 Resource Server para verificarlas; no poseen material de
firma. El gateway conserva rutas/CORS/rate limit, sin validacion JWT adicional.
Las firmas de enlaces/webhooks de pagos y las claves de API/internas mantienen
sus mecanismos propios: esta migracion cambia exclusivamente las sesiones JWT.

## Generar y montar una pareja

Se necesita OpenSSL para aprovisionar, no para ejecutar los servicios. En Windows
el helper tambien encuentra el OpenSSL de Git for Windows; no descarga herramientas.

```powershell
# Abortar si el destino ya existe. No rota ni sobrescribe una pareja.
.\scripts\generar-claves-jwt.ps1
.\preparar-env.ps1
```

El helper crea `/.secrets/jwt` ignorado por Git, restringe permisos antes de generar
y exporta privada PKCS#8 PEM (`BEGIN PRIVATE KEY`) y publica SPKI/X.509 PEM
(`BEGIN PUBLIC KEY`). En PowerShell no se usa criptografia casera ni se imprime PEM.
`preparar-env.ps1` detecta ese directorio y rellena su ruta absoluta si faltaba;
no reemplaza una ruta configurada ni sus archivos. Para otro destino usar
`-Directory <directorio-nuevo>` y configurar su ruta manualmente.

Alternativa Unix con herramientas estandar, en un directorio **nuevo**:

```sh
test ! -e .secrets/jwt || exit 1
umask 077
mkdir -p .secrets/jwt
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out .secrets/jwt/private.pem
openssl pkey -in .secrets/jwt/private.pem -pubout -out .secrets/jwt/public.pem
```

No ejecutar estos comandos sobre un directorio de rotacion existente. Guardar
copias de recuperacion seguras de la pareja; perder la privada activa exige una
rotacion y puede cortar sesiones si no quedan sus publicas disponibles.

```text
JWT_KEY_DIRECTORY/
  private.pem
  public.pem
  verification/       (opcional)
    anterior.pem      (solo publica SPKI)
    proxima.pem       (solo publica SPKI)
```

`JWT_KEY_DIRECTORY` es una **ruta absoluta host**, nunca texto PEM. Solo auth
recibe esa variable. Compose monta el directorio en `/run/secrets/jwt` read-only
y sin crear un origen vacio si falta. Nunca privada por build arg, Dockerfile o
variable compartida. Los Dockerfiles no necesitan las claves para construir.

En Unix, el proceso del contenedor corre como UID 10001: aprovisionar el directorio
para ese usuario o un grupo con acceso controlado (por ejemplo propietario 10001,
directorio 0700 y privada 0600). No resolver lectura con permisos para todo el
mundo. El helper restringe al usuario que lo ejecuta; el operador debe ajustar
propietario/grupo del bundle destinado a Docker Linux antes de arrancar. En Windows
se usan ACL del usuario y SYSTEM; no imprimir archivos al diagnosticar permisos.

Auth valida al iniciar formato, EC, parametros completos de P-256 y correspondencia
privada/publica mediante firma/verificacion de un desafio. Rechaza RSA, Ed25519,
otras curvas, SEC1, PEM cifrado no admitido y privadas en `verification/`.
No genera una pareja temporal como fallback ni registra el material o la causa
que pudiera contenerlo. Sin claves validas no arranca.

## Claims, kid y endpoint publico

Se conservan `sub`, `id`, `email`, `role`, `name`, `rut`, `iat`, `exp`, omitiendo
email/name/rut cuando son null. No se agregan issuer/audience/scope. TTL sigue
configurable mediante `JWT_TTL_MINUTES`, 15 minutos por omision, y la duracion de
refresh/familias/cookies no cambia despues del corte inicial.

El `kid` es el thumbprint RFC 7638 SHA-256 completo de la JWK publica, codificado
Base64URL sin padding (43 caracteres). Una misma publica conserva kid tras
reinicios o cambios de nombre del archivo. No se configura un id manual separado.

`GET /.well-known/jwks.json` de auth es publico, no requiere JWT y devuelve:

```json
{"keys":[{"kty":"EC","crv":"P-256","x":"<publico>","y":"<publico>","use":"sig","alg":"ES256","kid":"<thumbprint>"}]}
```

Solo contiene material publico, **nunca `d`**. Usa `Cache-Control: no-store` para
intermediarios; los verificadores mantienen su cache interna. No se agrega ruta
externa al gateway para el endpoint: los servicios consultan auth directamente.

Debt/payments leen `AUTH_JWKS_URI`. Para Maven local, `.env.example` indica
`http://127.0.0.1:8081/.well-known/jwks.json`. En el Compose incluido reciben
`http://ms-auth:8081/.well-known/jwks.json`; otras topologias deben configurar su
propia URL HTTP(S), sin credenciales embebidas. Proteger el transporte/red de esa
URL: la confianza en la firma depende de recibir publicas autenticas. No se usan
`jku`/`x5u` ni direcciones proporcionadas por un token.

Todos los verificadores restringen algoritmos a ES256 y exigen kid no vacio.
Rechazan HMAC, RSA y tokens sin firma; publicas desconocidas no autorizan.
Clock skew se fija a cero. Se exigen exp/iat validos y exp > iat, identidad string
sin coercion numerica y rol DEBTOR/CREDITOR/ADMIN. Se conserva fallback id -> sub
solo si falta id, y email O rut; ADMIN no necesita RUT. Los servicios mantienen
la autorizacion de cartera/empresa y el JwtPrincipal de su propio modulo.

## Corte inicial de sesiones

V7 de auth revoca todas las filas previas de `sessions` aun no revocadas, sin borrar
usuarios, familias, hashes o historial. Flyway la aplica una sola vez; reinicios o
rotaciones normales **no** vuelven a cerrar sesiones. Tambien quedan revocados los
miembros rotados de las familias previas. En una base nueva no hay filas que cambiar.

Al migrar desde HMAC, pausar login/refresh y detener **todas** las replicas antiguas
de auth antes de aplicar V7; no dejar un escritor viejo creando sesiones despues
del corte. Sustituir auth/debt/payments coordinadamente y reabrir trafico tras
validar salud y JWKS. Los access tokens anteriores se rechazan, las cookies viejas
no pueden canjearse por ES256 y refresh las elimina al responder 401. Todos deben
volver a entrar con codigo. No existe modo dual o fallback HMAC.

Rollback no restaura sesiones revocadas ni debe reactivar automaticamente firma
compartida. Mantener una ventana coordinada y el respaldo de datos/clave anterior
para investigar o recuperar mediante un plan revisado; nunca editar checksums de
migraciones aplicadas.

## Rotacion ordinaria ES256

1. Generar pareja nueva en otro directorio nuevo; no cambiar aun la activa.
2. Agregar su **publica** a `verification/` del bundle actual y recrear todas las
   replicas auth. Confirmar que todas publican old + new y siguen firmando old.
3. Esperar al menos cinco minutos desde que todas publican la union, o precalentar
   y verificar cada consumidor. No prometer disponibilidad si hay caches frias.
4. Preparar bundle con privada/publica nuevas activas y publica vieja en
   `verification/`. Recrear auth. Nueva emision lleva new kid; old sigue verificando.
5. Retener old desde la ultima emision vieja de **todas** las replicas hasta el TTL
   maximo efectivo de esos tokens (no asumir 15 si se configuro distinto), mas
   margen operacional. La tolerancia de reloj de los verificadores es cero.
6. Retirar old y recrear auth; verificar refresco. Caches pueden conservar esa
   publica unos minutos mas: la retirada no es revocacion instantanea. Para ese
   momento ya no deben existir tokens old vigentes.

El keyring se carga de forma inmutable al iniciar: no hay hot reload, panel o KMS.
La activa firma; la union de publicas verifica. Nunca hacen falta privadas antiguas
para verificar. Las refresh sessions nuevas sobreviven a esta rotacion normal.

## Cache, errores y rutas

Con Security 6.5.11/Nimbus 9.37.4, el decoder HTTP carga el JWKS a demanda, usa cache
en memoria de cinco minutos y refresca cuando no encuentra kid. No hace discovery
ni consulta red al arrancar con la URI explicita. El proceso pierde la cache al
reiniciar. Se fijan connect timeout 1 s y read timeout 2 s; estos no limitan toda
espera concurrente (el refresh compartido puede esperar hasta 15 s).

Con cache vigente y kid conocido, una caida corta de auth no impide verificar
localmente si no se fuerza refresco. Con cache fria/caducada o kid nuevo se necesita
auth. No hay stale-if-error adicional: fallo de recuperacion -> **503** cerrado;
JWT invalido o kid desconocido tras recuperar JWKS correcto -> **401**; rol/cartera
no autorizados -> **403**. Ninguna respuesta incluye tokens ni detalles internos.
Kids desconocidos pueden generar recuperaciones adicionales: no se garantiza
continuidad durante refrescos ni despues del TTL. Se conserva rate limit del gateway.

Auth verifica localmente, sin HTTP a si mismo. Debt separa /api/v1 (API key Bearer)
y /internal (X-Internal-Key) del portal JWT. Payments separa /internal, public y
webhooks: sus verificaciones de pago siguen obligatorias y un Bearer incidental
no bloquea un callback. Salud/Swagger siguen accesibles sin sesion; no se amplian
patrones publicos al portal completo.

## Verificacion

Las suites generan parejas P-256 efimeras; no usan Internet, llaves reales o sleeps
largos. Incluyen firma/claims, kid, manipulacion efectiva, algoritmos prohibidos,
curvas/pareja, publicacion sin privada, roles/principal y JWKS HTTP local con cache,
refresco, fallo y timeout. Ejecutar cada modulo con su wrapper/POM independiente.

La verificacion de entrega debe incluir imagenes y E2E en un proyecto Docker
aislado, volumen nuevo y correos reservados en Mailpit, con pasarelas simuladas.
No usar los contenedores, volumen ni `.env` real del equipo para probar V7 o rotacion.
No imprimir JWT/cookies/PEM/passwords al registrar evidencias.

### Harness E2E local

Tras construir las seis imagenes con una etiqueta local nueva `issue82-<sha>`:

```powershell
python scripts/verificar-jwt-local.py --image-tag issue82-<sha>
```

Requiere Python con `cryptography` ya disponible y contexto Docker local. No
instala paquetes ni descarga imagenes de aplicacion: deben estar construidas.
Crea nombres aleatorios, puertos localhost y volumen nuevo; no lee el `.env`
real. Arranca primero con Flyway target V6 para crear una cookie anterior al
corte, luego aplica V7 y comprueba su revocacion. Hace login real por Mailpit
para los tres roles, pruebas de ataques y rotacion con expiracion simulada
mediante tokens de prueba firmados. No espera 15 minutos ni introduce un reloj
de pruebas en la aplicacion productiva. Al terminar elimina solo su proyecto y
volumen nuevos; deja un informe JSON sin credenciales junto al checkout y un
scratch privado de revision. Nunca usar este harness contra una base real.
