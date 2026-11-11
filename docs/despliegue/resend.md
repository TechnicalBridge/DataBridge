# Correo transaccional con Resend

`ms-auth` entrega el MIME a `JavaMailSender`. El adaptador usa `POST /emails`
con el cliente HTTP de Java y el JSON de Spring. Así, las pruebas pueden apuntar a
un servidor HTTP local. DataBridge sigue generando y validando el OTP y conserva
el contrato interno de envío que usa `ms-debt`.

## Migración y reversión

1. Verifica en Resend el dominio del remitente. Publica los registros SPF y DKIM
   que entregue Resend y configura DMARC según la política del dominio. Habilita
   HTTPS saliente por el puerto 443 hacia `api.resend.com`.
2. Guarda una clave con permiso de envío en la configuración privada de
   `ms-auth`. Define `MAIL_TRANSPORT=resend`, `RESEND_API_KEY` y un
   `MAIL_FROM` del dominio verificado, por ejemplo `noreply@example.com`.
   Puedes configurar `MAIL_FROM_NAME` y `MAIL_REPLY_TO`. `RESEND_URL` usa por
   defecto el endpoint completo `https://api.resend.com/emails`.
3. Registra `https://<portal>/api/webhooks/resend` en Resend y guarda el secreto
   de firma como `RESEND_WEBHOOK_SECRET`. El gateway enruta únicamente el POST
   de esa ruta y `ms-auth` valida la firma Svix antes de leer el JSON.
4. Recrea `ms-auth`, comprueba el transporte en Reglas del panel y ejecuta la
   prueba manual con un buzón controlado.

Para revertir, selecciona `MAIL_TRANSPORT=brevo` con su clave y remitente
verificado, o `MAIL_TRANSPORT=smtp` con el host, puerto, autenticación y cifrado
del SMTP institucional. Recrea `ms-auth` para aplicar el cambio. Resend ofrece
también SMTP. En desarrollo se puede seleccionar `MAIL_TRANSPORT=mailpit`.
Con `MAIL_TRANSPORT` vacío se conserva la precedencia de main: Brevo con clave,
SMTP con host, luego Mailpit si se configuró el buzón.

## Entrega y diagnóstico

`mail_acceptance` registra la duración de la llamada y el ID opaco.
`resend_acceptances` registra la hora del 2xx. `resend_delivery_events` registra
`email.sent`, `email.delivered`, `email.delivery_delayed`, `email.bounced`,
`email.failed`, `email.suppressed` y `email.complained`. La clave primaria es
el ID Svix. Las tablas guardan identificadores y fechas, y los eventos guardan
el tipo. Los destinatarios, el asunto y los cuerpos quedan fuera de la telemetría.

Un 400/422 indica un pedido inválido. Ante 401/403 revisa la clave, los permisos
y el dominio. Un 429 puede indicar un límite de solicitudes o de cuota según
el plan. Un 5xx indica un error del proveedor. Un timeout deja la aceptación
indeterminada. El adaptador realiza un POST por envío, sin reintentos propios
ni fallback. **PENDING INTEGRATION WITH #402**. El futuro outbox debe colocar
el mismo UUID de trabajo en el header interno MIME `X-DataBridge-Mail-Job-Id`
para cada intento de un mismo envío. Resend recibe `Idempotency-Key: mail-job/<uuid>`
y el mismo contenido. Si no existe un UUID estable, el adaptador omite la clave.
La ventana de idempotencia de Resend es de 24 horas.

Flyway crea ambas tablas con `V9__resend_delivery_events.sql`. En una base local
donde se aplicó el borrador repetible, revisa primero el historial de Flyway. Si
la única migración ausente es `R__resend_delivery_events.sql`, respalda la base,
ejecuta `flyway repair` con la misma configuración de migraciones y vuelve a
arrancar. V9 conserva las tablas y los datos existentes. Al integrar #402,
reconcilia el orden de las migraciones antes de aplicarlas en una base compartida.

## Prueba manual de OTP y latencia

En un entorno controlado, solicita un OTP, comprueba su llegada e inicia sesión
con él. Registra proveedor, `request_at`, `accepted_at`, `delivered_at` e
`inbox_seen_at`. Usa el ID del proveedor para correlacionar aceptación y entrega.
Calcula `accepted_at - request_at` y `delivered_at - accepted_at` por separado.
Repite con Brevo en condiciones equivalentes, idealmente con al menos diez
muestras por proveedor si la cuota lo permite. Reporta mediana, p95, mínimo y
máximo. La latencia real queda sin medir.

[API de envío](https://resend.com/docs/api-reference/emails/send-email) ·
[Firma de webhooks](https://resend.com/docs/webhooks/verify-webhooks-requests) ·
[Idempotencia](https://resend.com/docs/dashboard/emails/idempotency-keys)
