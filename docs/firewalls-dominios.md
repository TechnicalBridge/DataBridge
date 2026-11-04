# Firewalls lógicos por dominio (#251)

Base auditada: 69204dd647a45dd0042115a1203faf141c1374cd. Matriz previa a implementación.

| Dominio / control | Estado inicial | Evidencia | Riesgo de duplicar |
| --- | --- | --- | --- |
| Auth: cuota IP y proxies | EXISTE | gateway RateLimitFilter / ClienteReal; nginx sobrescribe X-Forwarded-For | Alto |
| Auth: intentos por código y expiración | EXISTE | AuthService, AccessCode, MagicLink, SessionService; bloqueo de filas | Alto |
| Auth: identificador transversal y bloqueo progresivo configurable | PARCIAL | límite por código; nuevo código reinicia sus intentos; no cuota transversal de solicitudes por identidad | Gap real |
| Debt: JWT / API key / clave interna | EXISTE | SecurityConfig, ApiKeyService, IntegracionController, InternalController | Alto |
| Debt: allowlist opcional por integración | FALTA | no política CIDR en gateway ni servicio | Gap real |
| Debt: validación / SQL | EXISTE/PARCIAL | CarteraIntakeService, DTOs, repositorios JPA; multipart 5 MB y nginx 5 MB | No regex SQL ni parser propio |
| Payments: autenticidad | EXISTE | PaymentService, FirmaDeKhipu, WebhookVerifier, WebpayClient, MercadoPagoClient | Alto |
| Payments: duplicados / replay | EXISTE | PaymentService.confirmar, repositorios; consulta de estado al proveedor | Alto |
| Payments: UF saliente | EXISTE | BancoCentralClient: TLS configurado por URL, conexión 10s, request 20s, validación de respuesta | No firewall entrante |
| Network: exposición prod | EXISTE | compose prod elimina ports de bases, Rabbit y portal; servicios/gateway sin ports; Caddy 80/443 | No segmentación adicional sin evidencia |
| Audit: bloqueos explícitos por dominio sin PII | PARCIAL | bitácoras auth/pagos; gateway solo registra método y path | Completar sin cuerpos ni secretos |

Los firewalls son capas lógicas: gateway y controles del servicio propietario. No son un WAF ni máquinas nuevas. No se duplican JWT ES256/JWKS, firmas oficiales, idempotencia, JPA ni aislamiento existente.

Refresh/logout conservan cuota general (120/min por IP); acceso/codigo/enlace/verify usan cuota auth (10/min por IP). OPTIONS no consume cuota. El gateway solo cree X-Forwarded-For de TRUSTED_PROXIES y nginx lo sobrescribe. CORS conserva orígenes explícitos.

Webpay confirma el token contra Transbank; no se inventa una firma para su callback. Khipu valida HMAC de timestamp+cuerpo y consulta estado remoto; no exige frescura, pues repetir un aviso no crea otro abono. Mercado Pago consulta el proveedor; retornos propios llevan firma. La pasarela simulada usa HMAC propio, separado de los proveedores reales. Las reglas criptográficas permanecen en ms-payments.

La revisión no certifica seguridad absoluta. Docker E2E requiere daemon disponible; `docker info` inicialmente no pudo conectarse al engine local.

## Nuevos controles y operación

- Auth: `AUTH_IDENTIFIER_LIMIT_CAPACITY=10`, `AUTH_IDENTIFIER_LIMIT_WINDOW_SECONDS=60`, `AUTH_IDENTIFIER_LIMIT_MAX_BLOCK_SECONDS=900`. Cuota por operación e identidad normalizada, almacenada como SHA-256, antes de consultar existencia. `codigo` y `enlace` comparten cuota de envío; `acceso` tiene cuota independiente. Cuenta todas las solicitudes, incluidas las correctas: complementa el contador existente de errores por código. La reincidencia duplica el bloqueo hasta el máximo; tras inactividad se olvida. Configuración inválida falla al iniciar (máximo 86400s). El contador es local a la instancia y se reinicia con ella; no es un bloqueo distribuido. Máximo 100000 entradas; al agotarse capacidad sin entradas vencidas, nuevas identidades reciben 429 (fail closed). No se guardan ni registran códigos ni identidades en claro. Refresh y logout mantienen su política previa; tokens de magic link conservan su cuota por IP y su consumo único.
- Debt: `DEBT_INTEGRATION_ALLOWED_CIDRS` vacío desactiva el control. Formato `sha256_de_la_api_key=CIDR,CIDR;otra_huella=CIDR`. Admite IPv4/IPv6; configuración inválida/duplicada falla al iniciar sin imprimirla. Cada entrada restringe exclusivamente esa clave, incluidas varias claves de la misma integración; al rotar claves se debe actualizar la política. Claves sin entrada conservan autenticación y política previas. La dirección procede de `ClienteReal`; nunca se cree a un proxy no confiable. Denegación 403, sin tocar JWT, rutas internas ni webhooks; OPTIONS pasa sin restricción. La allowlist no sustituye la validación/revocación de API key en el servicio.
- Audit: eventos `firewall domain=... rule=... outcome=denied status=...` para cuota IP/identidad, CIDR e inválidas firmas Khipu/simuladas. Sin URL, query, identidad, IP, key, JWT ni body en estos eventos. Se conservan bitácoras existentes. No se convierten 401 ordinarios en ERROR.
- Payload: nginx limita a 5 MB y ms-debt limita multipart a 5 MB; la ingesta valida por fila para conservar aceptación parcial. No se agrega un filtro de cuerpos, límites nuevos de lotes ni regex de SQL/XSS que cambien el contrato. Los repositorios siguen usando JPA y parámetros tipados. Un acceso directo de desarrollo al gateway no equivale al límite de entrada de nginx.
- Fail closed: JWT, API key, firma y rechazo CIDR. La observabilidad utiliza SLF4J y no consulta servicios externos. No se elimina ningún header necesario para callbacks. No se amplían CORS ni puertos ni redes.

## Verificación y rollback

Ejecutar con JDK 25 las suites gateway, ms-auth, ms-debt y ms-payments. Las pruebas del identificador usan reloj controlado, sin sleeps. Las pruebas CIDR cubren claves aisladas, ambos protocolos y spoofing de X-Forwarded-For. Las suites previas cubren firmas/idempotencia y JWT/clave interna. Docker Compose debe validarse con un env ficticio y stack/proyecto aislado.

Rollback: revertir los commits de #251. Para retirar solo la allowlist, vaciar `DEBT_INTEGRATION_ALLOWED_CIDRS` y reiniciar el gateway. Las cuotas por identidad se ajustan mediante sus tres variables; no hay migraciones ni cambios en datos. No se alteran JDK 25, ES256/JWKS, proveedores LLM ni frontend.
