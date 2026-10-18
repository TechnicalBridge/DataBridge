# Estado del despliegue — 18 de octubre de 2026

Rama de respaldo: `despliegue_microservicio`. No fusionar a main sin revisión humana.

## Proyecto desplegado

- Railway: https://railway.com/project/74e6c826-3736-46f4-9ff8-29b6ab2bfcf7
- Entorno: production.
- Portal: https://portal-production-e465.up.railway.app
- Servicios observados en línea: portal, gateway, ms-auth, ms-debt, ms-payments, ms-ai, MySQL y rabbitmq.
- Las configuraciones se aplicaron en Railway; este documento registra el estado y no constituye una exportación automática de la infraestructura.

## Servicios y conexiones

| Servicio | Raíz | Puerto |
| --- | --- | --- |
| portal | /frontend | 8080 |
| gateway | /gateway | 8082 |
| ms-auth | /ms-auth | 8081 |
| ms-debt | /ms-debt | 8083 |
| ms-payments | /ms-payments | 8084 |
| ms-ai | /ms-ai | 8085 |

Solo el portal tiene dirección HTTP pública. El portal usa `GATEWAY_HOST=${{gateway.RAILWAY_PRIVATE_DOMAIN}}` y `GATEWAY_PORT=8082`. El Gateway enruta a los cuatro microservicios por su dominio privado y puerto correspondiente. Los servicios Java usan `SERVER_ADDRESS=::` y `JAVA_TOOL_OPTIONS=-Xms32m -Xmx256m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC`.

Auth, Deuda y Pagos comparten el motor MySQL mediante referencias a MYSQLHOST, MYSQLPORT, MYSQLUSER y MYSQLPASSWORD del servicio MySQL. Sus esquemas son independientes. JWT_SECRET e INTERNAL_KEY se referencian desde ms-auth; no se incluyen sus valores.

PUBLIC_URL en Auth y Pagos apunta al portal HTTPS. Auth utiliza COOKIE_SECURE=true. El correo administrador se configura únicamente en Railway.

## RabbitMQ

Imagen oficial: `rabbitmq:4-management`. Servicio privado `rabbitmq`, puerto AMQP 5672, volumen en `/var/lib/rabbitmq` e identidad estable `RABBITMQ_NODENAME=rabbit@localhost`.

Variables del broker:

```dotenv
RABBITMQ_DEFAULT_USER=databridge
# RABBITMQ_DEFAULT_PASS: secreto generado en Railway; conservar el existente.
RABBITMQ_NODENAME=rabbit@localhost
RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2 -rabbit vm_memory_high_watermark {absolute,268435456} disk_free_limit 134217728
TZ=America/Santiago
```

En ms-debt y ms-payments:

```dotenv
EVENTS_RABBIT=true
RABBIT_HOST=${{rabbitmq.RAILWAY_PRIVATE_DOMAIN}}
RABBIT_PORT=5672
RABBIT_USER=${{rabbitmq.RABBITMQ_DEFAULT_USER}}
RABBIT_PASSWORD=${{rabbitmq.RABBITMQ_DEFAULT_PASS}}
```

Flujo de eventos:

```mermaid
flowchart LR
  P[ms-payments] -->|pago.confirmado| R[RabbitMQ]
  R -->|ms-debt.pagos-confirmados| D[ms-debt]
```

Exchange durable `tbridge.pagos`; routing key `pago.confirmado`; cola durable `ms-debt.pagos-confirmados`. Ambos servicios declaran la misma topología. Las flechas de Railway representan referencias de variables, no la dirección de entrega de los mensajes.

Pagos tiene healthcheck `/actuator/health`, que incluye RabbitMQ al activar EVENTS_RABBIT. Se verificaron conexiones AMQP exitosas de ambos servicios y despliegue saludable de Pagos. El umbral de memoria del broker regula publicación: no es un límite estricto de RAM del contenedor.

## Verificación y pendientes

- Portal y documentación OpenAPI de los cuatro servicios respondieron HTTP 200.
- Acceso sin sesión: `/api/me` y chat de IA rechazaron la solicitud con 401.
- No se ha verificado un pago completo con entrega del evento y actualización de deuda.
- Webpay está en integración TEST; no se declara habilitado para cobros reales.
- ms-ai funciona con reglas mientras no se configure proveedor LLM.
- Brevo: falta verificar BREVO_API_KEY y remitente validado MAIL_FROM. No se confirma envío de códigos ni creación del deudor solicitado.
- APOFYX: cambios locales en su propio repositorio, rama `issue-24-desplegar-apofyx-railway`, commit `13262aa`, issue TechnicalBridge/APOFYX#24. La subida fue rechazada por permisos Git (403); no está incluido ni desplegado por esta rama de TB_web.
- Contenedores locales detenidos por solicitud del usuario para reducir carga del equipo.

No guardar secretos, códigos de acceso ni archivos .env en Git. No regenerar credenciales de RabbitMQ después de inicializar el volumen sin planificar su cambio en el broker y sus clientes.
