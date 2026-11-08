# Issue 308: auditoría previa y diseño

Base: `95a5c4de45d394acdcf0bf5939f954332430bab1`.
Baseline JDK 25: ms-auth 322, ms-debt 533; sin fallos.

| Flujo | Crea OTP | Envía | Transacción actual | Espera/falla actual |
|---|---|---|---|---|
| Deudor solicita código | AuthService/MagicLink | MailService @Async después del commit | creación sí; envío no | respuesta rápida; fallo solo log; siguiente petición anula anterior |
| Personal solicita código | AuthService/MagicLink | igual | igual | igual |
| Respaldo magic link | AuthService/MagicLink, mismo acceso | igual | igual | código y enlace comparten consumo |
| Invitación inicial de deuda | InternalController/AuthService | MailService síncrono | InvitacionService AFTER_COMMIT + REQUIRES_NEW | HTTP remoto dentro de transacción; recuperación por pendientes |
| Invitaciones pendientes | igual | igual | bucle @Transactional | latencia lineal; corta ante fallo proveedor |
| Toque campaña | igual | igual | ContactoService @Transactional | latencia lineal; restricciones por contactos existentes |
| Recordatorio cuota | igual | igual | RecordatorioService @Transactional | latencia lineal; unique cuota |
| Reenvío manual/interno | igual | igual | AccesoService @Transactional | espera aceptación, 502 y anulación si falla |
| Invitación de personal | PersonalService/MagicLink | MailService @Async | creación transaccional | envío en memoria existente |

RabbitMQ actual transporta pagos; no se reutiliza su contrato para correo.
SMTP y Mailpit se mantienen. HTTP 201 significa aceptación API, nunca entrega al receptor.
La latencia API se mide separada de queued→delivered/deferred. DataBridge no controla el tiempo de llegada externo.

Diseño: cooldown y gracia acotada sin credenciales reversibles; outbox de intenciones de contacto sin OTP; despacho fuera de transacciones y confirmación posterior; webhook persistente sin payload ni email.

## OTP y portal

Defaults: `OTP_RESEND_COOLDOWN_SECONDS=60` (minimo efectivo 60),
`OTP_PREVIOUS_CODE_GRACE_SECONDS=180` (0 desactiva gracia; maximo 900).
Durante el cooldown se acepta sin crear fila ni mandar duplicados. Despues se genera
un acceso nuevo, conservando solo el anterior por el menor de su TTL y la gracia.
No se reconstruye el codigo previo: solo se guardan hashes. El consumo de cualquiera
retira los hermanos; los errores de comprobacion gastan intentos en ambos.
Las 256 franjas de `otp_identity_locks` existen antes del primer pedido y se bloquean
transaccionalmente en DB, tanto al emitir como al consumir. Colisiones entre franjas
solo serializan identidades diferentes; no comparten accesos.

El portal conserva un timestamp por portal en sessionStorage; no guarda identificador,
OTP ni token. Bloquea tanto envio normal como respaldo durante 60 segundos y protege
doble clic antes de que React renderice. Cambiar identidad conserva este cooldown local.
El backend aplica su propia politica entre instancias; el boton no es una defensa de seguridad.
El envio interactivo conserva el @Async existente: no es una cola durable de OTP.
Una caida entre commit y envio puede perder ese mensaje; al terminar el cooldown se
puede pedir otro, conservando el previo durante la gracia.

## Outbox y contrato

`contact_jobs` guarda referencias internas, tipo, llave idempotente, huella de destino,
estado y tiempos. No guarda OTP, token, API key ni cuerpo de correo. Se confirma en la
misma transaccion que selecciona invitacion, toque, recordatorio o reenvio manual.
El listener de invitacion AFTER_COMMIT existente sigue vigente.
RabbitMQ de pagos, sus contratos, SMTP y Mailpit permanecen como estaban.

El worker ejecuta cuatro tareas como maximo por instancia, sin cola de memoria
(SynchronousQueue + rechazo AbortPolicy). El exceso espera en DB. La seleccion
pagina cuatro IDs, y actualiza el turno de un trabajo diferido para evitar que bloquee
a los demas. Los @Async de cada servicio tienen core 2, max 4 y capacidad 100. En ms-debt,
CallerRunsPolicy aplica backpressure al listener si se llena: el caller solo encola
en DB y nunca espera al proveedor. En ms-auth el rechazo es AbortPolicy.
En varias replicas estos limites son por instancia; el cupo de invitaciones existente
tambien conserva su alcance por instancia.

Una fila de deudor bloqueada serializa reservas y reclamaciones. La clave unica
identifica cuota, toque/campana o invitacion/deuda/lote; reingresar una deuda en un
lote nuevo permite una invitacion nueva, sujeta al limite vigente. Una reserva activa
impide que otras deudas del mismo deudor generen contactos simultaneos.
El worker vuelve a comprobar estado, suspension, destino, opt-out de recordatorios,
campana, su asignacion actual a la deuda, horario, limite e invitaciones. Difiere si no hay ventana, con margen de 30 s
al cierre. No mantiene transaccion mientras espera ms-auth ni Brevo.
Se preservan las consultas y restricciones unicas de contactos/cuotas existentes.

| Estado | Significado |
| --- | --- |
| QUEUED | Intencion persistida; aun no es contacto enviado |
| SENDING | Reserva confirmada antes del HTTP |
| ACCEPTED | ms-auth/proveedor acepto; crea el contacto, nunca confirma delivered |
| FAILED | Rechazo conocido; no crea contacto; el operador corrige la causa |
| UNCERTAIN | Transporte ambiguo o SENDING de mas de 120 s; requiere conciliacion |
| CANCELLED | La intencion dejo de ser elegible |

Los fallos al establecer conexion con ms-auth (conexion rechazada, DNS o ruta no disponible)
se distinguen mediante una excepcion interna sin PII. Como no se envio la solicitud, el mismo
trabajo vuelve a QUEUED con `next_attempt_at` persistido: espera 30 s tras el primer intento
y 60 s tras el segundo. El limite es tres intentos de despacho en total. Al agotarlo pasa a
FAILED, conserva el diagnostico y libera la reserva del deudor; no se convierte en UNCERTAIN.
El poll y la reclamacion bloqueada respetan la fecha, sin sleeps ni HTTP en transacciones.
La espera por horario/tope no consume intentos. Un timeout de lectura, reset de conexion o
respuesta cuyo resultado no pueda garantizarse NO se reintenta por esta via: queda UNCERTAIN.
Un error HTTP generico tampoco se presume previo al envio. Se preserva la politica de Brevo
para respuestas de proveedor: estos reintentos nuevos solo cubren establecer conexion con ms-auth.

La migracion debt V15 agrega el contador y la proxima fecha, conservando los trabajos existentes.
No libera UNCERTAIN anteriores: sin el diagnostico original no se puede demostrar que no
hubo envio. Esos casos siguen requiriendo conciliacion antes de cualquier reencolado.
Un toque se cancela si la deuda paso a otra campana (o perdio la asignacion) mientras estaba en cola;
no se envia ni se registra un contacto de la campana anterior.

Una caida conserva QUEUED. SENDING abandonado pasa a UNCERTAIN; no se reenvia a ciegas.
Si el commit de confirmacion falla despues del HTTP se aplica la misma politica.
La direccion corregida permite volver a encolar un FAILED en la siguiente seleccion.
Un CANCELLED puede volver a encolarse cuando el selector lo encuentre elegible otra vez
(por ejemplo, al reactivar una empresa o reanudar una campana). Un error de configuracion no reintenta permanentemente: tras corregirlo,
un operador puede reencolar el trabajo FAILED. UNCERTAIN no se libera sin verificar
el resultado para no duplicar contactos. No hay garantia exactly-once del proveedor.

Compatibilidad: `/internal/codigos` mantiene respuesta 200 sincrona de aceptacion y
502 al rechazar; se invoca desde el worker. Los endpoints de seleccion conservan 200
y nombres legacy `enviados`/`invitados`, ahora con `estado=QUEUED`: esos contadores son
intenciones encoladas. `/api/debts/{id}/codigo` retorna `estado=QUEUED`, `enviado=false`,
sin OTP y sin vencimiento hasta emitirlo. El portal dice «Solicitud aceptada».
Los contactos de negocio siguen contando aceptacion, no entrega por webhook.

## Reintentos y SMTP

Brevo: tres intentos totales; solo 429, 500, 502, 503, 504 o IOException/timeout.
400, 401/403 y demas errores permanentes no reintentan. Backoff de 250/500 ms
mas jitter uniforme del mismo rango. `Retry-After` acepta segundos o fecha HTTP;
si supera el presupuesto se falla sin reintentar antes del plazo. Presupuesto global
15 s, timeout de cada intento limitado al remanente y a SMTP_TIMEOUT_MS (default 10 s).
El transporte ms-debt→ms-auth usa connect 5 s y read 20 s. Los reintentos de un envio
conservan exactamente el cuerpo y el codigo en memoria. Un timeout puede ocurrir
tras la aceptacion remota: no se promete ausencia absoluta de mensajes duplicados.

SMTP sigue siendo alternativa de configuracion cuando falta BREVO_API_KEY y Mailpit
conserva las reglas de dominios reservados/desvio. No se agrega failover SMTP tras
Brevo: un resultado ambiguo no justifica una segunda entrega por otro canal.
No se envia por ambos despues de un 2xx. Los logs de MailService ya no incluyen
codigos, enlaces de acceso, destinatarios completos ni texto remoto de error.
Tags del proveedor: otp, invitation, reminder, access_code, manual. Son categorias
fijas sin RUT, email ni deuda; access_code agrupa campana y reenvio manual.

## Webhook

URL: `POST https://<portal>/api/webhooks/brevo`. El gateway abre solo este POST exacto;
no abre `/internal/**`. Configurar un webhook transaccional **individual**, sin batch,
con header personalizado `X-Brevo-Secret` y un secreto dedicado aleatorio de al menos
32 caracteres en BREVO_WEBHOOK_SECRET. Vacio o demasiado corto rechaza todos los eventos.
No usar BREVO_API_KEY, INTERNAL_KEY ni claves JWT. La comparacion es constante.
No configurar Bearer del portal; este endpoint autentica mediante su header dedicado.

Segun las opciones de la cuenta, seleccionar delivered, deferred, softBounce,
hardBounce, blocked, error e invalid; sent produce payload `request`.
Los payloads de rebote usan `soft_bounce`/`hard_bounce`. Se guarda message-id normalizado,
evento, ts_event (UTC) y received_at. No se guarda el payload, email, asunto ni razon.
La clave unica SHA-256(message-id + evento + segundo) evita duplicar un evento reenviado,
incluso entre instancias. No se reemplaza un estado unico: la historia admite eventos
fuera de orden. Un evento individual valido responde 204 sin llamar al proveedor.
Se permiten eventos que lleguen antes de guardar la aceptacion, para correlacion posterior.

| Evento | Interpretacion |
| --- | --- |
| 2xx de envio | API acepto el mensaje; no confirma que llego |
| delivered | El servidor receptor acepto el mensaje; no implica lectura ni inbox |
| deferred | Entrega retrasada |
| soft_bounce | Fallo temporal de entrega |
| hard_bounce | Fallo permanente de entrega |
| blocked / error / invalid | Entrega bloqueada o fallida |

Brevo documenta cuatro reintentos de webhook, con incrementos 10 min, 1 h, 2 h y 8 h,
pero descarta ciertos errores HTTP (4xx salvo 429 y 5xx). Por eso no se depende de
reintentos ilimitados y se debe vigilar la recepcion. Al fallar DB, el endpoint falla;
no confirma un evento que no pudo persistir. Conciliar con los registros del proveedor.

Probar con un payload ficticio, un message-id opaco y un ts_event reciente, usando
el header de prueba; repetirlo debe dejar una sola fila. Sin header o con secreto
incorrecto: 401. Evento/fecha invalida: 400. Las pruebas usan H2 y HTTP simulado.
No activar credenciales ni destinatarios reales para probar.

## Consultas operativas sin PII

En ms-debt:

```sql
SELECT state, COUNT(*) FROM contact_jobs GROUP BY state;
SELECT id, kind, state, created_at, updated_at
FROM contact_jobs WHERE state IN ('FAILED', 'UNCERTAIN') ORDER BY updated_at;
-- Solo despues de corregir un fallo conocido y verificar que NO fue aceptado:
UPDATE contact_jobs SET state='QUEUED', dispatch_attempts=0,
    next_attempt_at=CURRENT_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP(6)
WHERE id=:job_id AND state='FAILED';
```

Para UNCERTAIN, el operador concilia con los logs del proveedor y el registro de
contactos antes de decidir; no usar el UPDATE anterior para liberarlo indiscriminadamente.

En ms-auth:

```sql
SELECT event_type, COUNT(*) FROM mail_delivery_events GROUP BY event_type;
SELECT e.event_type,
       AVG(TIMESTAMPDIFF(MICROSECOND,a.accepted_at,e.event_at)/1000) AS avg_latency_ms
FROM mail_delivery_events e JOIN mail_acceptances a ON a.message_id=e.message_id
WHERE e.event_type IN ('delivered','deferred') AND e.event_at>=a.accepted_at
GROUP BY e.event_type;
SELECT MAX(received_at) AS last_event FROM mail_delivery_events;
```

La latencia API se registra por intento con provider, attempt, status, outcome y duration_ms;
la cola registra job_id opaco y outcome. Las tablas permiten medir aceptacion→evento,
que no equivale al tiempo hasta que una persona lee el mensaje. Aplicar la retencion
operativa de telemetria; estas tablas no incluyen un borrado automatico nuevo.

## Verificacion y limites

La fixture `frontend/tests/access-cooldown.cjs` requiere Playwright 1.64.1 o un modulo
compatible (PLAYWRIGHT_MODULE permite apuntar a playwright-core instalado), Chromium y
Vite en localhost:5308. Intercepta toda API y bloquea origenes externos; ejecuta los tres
portales en 390/1360 px y claro/oscuro, con reloj simulado y screenshots locales.
La integracion JPA/H2 verifica la cola durable y dos schedulers simultaneos; no reemplaza
la validacion de migraciones y locks con MySQL real. Docker no estaba activo en esta
estacion, por lo que el E2E MySQL/Mailpit en contenedores queda pendiente de revision.
Las pruebas de fallos que antes pertenecian a los selectores se trasladaron al worker,
donde ahora ocurren la aceptacion, los rechazos y la actualizacion del deudor.

Fuentes oficiales consultadas:
[envio transaccional](https://developers.brevo.com/docs/send-a-transactional-email),
[eventos](https://developers.brevo.com/docs/transactional-webhooks),
[autenticacion de webhook](https://developers.brevo.com/docs/secured-webhooks),
[reintentos de webhook](https://developers.brevo.com/docs/retry-mechanism),
[limites API](https://developers.brevo.com/docs/api-limits).
