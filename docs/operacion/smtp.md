# SMTP de producción sin Brevo (#310)

Arquitectura: ms-auth → SMTP_HOST:SMTP_PORT → relay institucional o propio → MX destinatario.
Spring Boot / Jakarta Mail ya implementan el cliente SMTP; no se descubre MX desde ms-auth.
ms-debt conserva su contrato HTTP con ms-auth para invitaciones, recordatorios y campañas.
No se agrega un MTA al compose: operar Postfix, Exim o Haraka corresponde a infraestructura.

## Selección determinista

| MAIL_TRANSPORT | Requisito | JavaMailSender | Salida / uso |
| --- | --- | --- | --- |
| smtp | SMTP_HOST | Spring Mail | Relay real, producción |
| brevo | BREVO_API_KEY | CorreoPorBrevo | API SaaS opcional |
| mailpit | CORREO_BUZON_DE_PRUEBA=mailpit:1025 | Spring Mail | Captura local, nunca producción |
| vacío | Resolución automática | Según configuración | SMTP_HOST → Brevo → buzón local → sin correo |

Una clave antigua no cambia SMTP por Brevo. Si antes había ambos configurados, fijar
MAIL_TRANSPORT=brevo para conservar el proveedor anterior. Modo desconocido o incompleto falla
al iniciar. Sin ninguno no hay sender y se conserva el registro local de accesos; no usarlo en
producción. No hay conmutación automática entre proveedores ni nuevos reintentos SMTP: ante
aceptación incierta, conciliar con el relay antes de reemitir para evitar duplicados.

## Variables y TLS

```dotenv
MAIL_TRANSPORT=smtp
SMTP_HOST=mail.example.com
SMTP_PORT=587
SMTP_USER=
SMTP_PASSWORD=
SMTP_AUTH=true
SMTP_STARTTLS=true
SMTP_SSL=false
SMTP_TIMEOUT_MS=10000
MAIL_FROM=noreply@example.com
MAIL_FROM_NAME=DataBridge
MAIL_REPLY_TO=
BREVO_API_KEY=
```

Completar secretos en ejecución: este ejemplo incompleto falla hasta establecer usuario y contraseña.
MAIL_FROM debe estar autorizado y alineado con el dominio autenticado. MAIL_FROM_NAME y
MAIL_REPLY_TO se conservan. BREVO_URL solo configura la API opcional; no es necesario para SMTP.

| Puerto | Configuración |
| --- | --- |
| 587 | Submission: STARTTLS=true, SSL=false. STARTTLS es obligatorio; falla si no está disponible |
| 465 | TLS implícito: SSL=true, STARTTLS=false |
| 25 | Relay interno o MTA: auth y TLS según política del servidor; preferir TLS |

SSL y STARTTLS simultáneos se rechazan. AUTH=true exige usuario y contraseña. Puerto válido y
timeouts positivos son obligatorios. SMTP_TIMEOUT_MS cubre conexión, lectura y escritura.
Se verifica certificado e identidad del servidor, sin trust-all. Para CA privada, montar un
truststore externo de solo lectura y usar `-Djavax.net.ssl.trustStore=/ruta/truststore` en la JVM;
gestionar su contraseña por el mecanismo de secretos del despliegue. No versionar claves ni
certificados privados. [Propiedades Angus SMTP](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html).

## Docker y firewall

El compose pasa MAIL_TRANSPORT y SMTP_* a ms-auth. El override de producción desactiva Mailpit
y vacía CORREO_BUZON_DE_PRUEBA: no usar MAIL_TRANSPORT=mailpit allí.
Permitir salida de ms-auth al único puerto necesario del relay. Internet no necesita alcanzar
ms-auth por SMTP. No publicar 25, 465 y 587 indiscriminadamente. Un relay autogestionado debe
limitar clientes por red interna o autenticación: nunca aceptar relay abierto hacia cualquier dominio.

## DNS y entregabilidad

Docker no proporciona reputación ni una IP apta para correo. Un MTA propio requiere IP pública
estable, puerto saliente permitido, hostname válido y TLS. EHLO/HELO debe identificar el hostname
del MTA, no localhost. El proveedor VPS/cloud/ISP configura PTR/rDNS de la IP saliente, coherente
con hostname y resolución directa: Docker no puede hacerlo.

- SPF: autorizar la IP o infraestructura que realmente entrega. Ejemplo conceptual:
  `example.com TXT "v=spf1 ip4:203.0.113.10 ~all"`. Sustituir por infraestructura verificada;
  no copiar literalmente ni publicar dos registros SPF.
- DKIM: firmar en el relay/MTA y publicar su selector/clave pública en DNS. ms-auth no firma manualmente.
- DMARC: alinear MAIL_FROM con SPF o DKIM. Comenzar con monitoreo `p=none` y un buzón de informes
  administrado; verificar todos los emisores antes de endurecer. No comenzar directamente con p=reject.
- El equipo que opera el relay asume reputación, listas de bloqueo, rebotes, límites, calentamiento
  de IP y DNS. SMTP propio no garantiza entrega gratuita ni inmediata.

Referencias: [SPF RFC 7208](https://www.rfc-editor.org/rfc/rfc7208),
[DKIM RFC 6376](https://www.rfc-editor.org/rfc/rfc6376), [DMARC RFC 7489](https://www.rfc-editor.org/rfc/rfc7489).

## Diagnóstico y aceptación

/admin/reglas usa ReglasService y muestra SMTP host:puerto, Brevo, buzón local o ausencia de
servidor. Nunca muestra usuario, contraseña ni API key. `mail_acceptance` registra transport,
outcome=accepted/rejected, duration_ms y error_category sin cuerpo, OTP ni destinatarios.
Para SMTP, duration_ms representa SMTP_ACCEPTANCE_LATENCY: tiempo hasta aceptación del relay,
no hasta inbox. SMTP 250 no prueba entrega. Un fallo puede tener aceptación parcial o incierta:
rejected describe la operación observada, no autoriza a reenviar automáticamente.

| Síntoma | Revisar |
| --- | --- |
| Connection refused / server unavailable | Servicio, host/puerto, DNS, firewall y ruta desde el contenedor |
| Authentication failed | Credenciales y permiso de submission; no imprimir secretos |
| STARTTLS required | Puerto 587, STARTTLS=true, SSL=false |
| Certificate error / TLS failure | Host del certificado, vigencia y CA/truststore; no desactivar validación |
| Relay access denied | ACL de red o autenticación; no abrir el relay a Internet |
| Sender rejected | MAIL_FROM autorizado y dominio autenticado |
| Recipient rejected | Dirección y política del relay; no repetir indefinidamente |
| Timeout | Conectividad y disponibilidad; conciliar aceptación antes de repetir |
| Aceptado pero spam / no llega | Cola, rebotes, SPF/DKIM/DMARC/PTR y reputación en el MTA |

## Verificación sin correo real

`mvnw -f ms-auth/pom.xml clean test` usa GreenMail en loopback, con Brevo vacío: solicitud HTTP
OTP → SMTP de captura → canje HTTP → sesión. Repositorios y ms-debt están simulados: esta prueba
no reemplaza E2E con contenedores y MySQL. También se verifican código, invitación, recordatorio
y prueba de correo. El código de campañas comparte MailService, sin cambiar el contrato de ms-debt.
GreenMail no entrega a Internet. Las regresiones Brevo usan HTTP simulado y Mailpit conserva captura
local. El umbral local es 15 s para cuatro aceptaciones; no es un SLA de inbox.
