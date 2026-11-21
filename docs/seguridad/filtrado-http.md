# Evaluación de filtrado HTTP adicional (#313)

## Superficie actual

El servidor publica 80/443 hacia Caddy; `deploy/preparar-servidor.sh` deja UFW
abierto también en 22 para SSH con llave y fail2ban. `deploy/Caddyfile` reenvía
al portal nginx, que envía `/api` al gateway. En producción solo Caddy publica
puertos; las rutas `/api/auth`, `/api/debts`, `/api/payments` y `/api/ai` llegan
al gateway. `RateLimitFilter` aplica cupos por IP (10/min para autenticación,
120/min generales por omisión); la aplicación comprueba JWT, autorizaciones,
firmas de webhooks y valida su entrada en los servicios.

Esto contiene intentos repetidos desde una IP, pero **no inspecciona** patrones
de inyección, escaneo distribuido ni cuerpos anómalos antes de entrar a Java.
Un WAF tampoco sustituye consultas parametrizadas, autorización por objeto,
firmas de callbacks ni límites de tamaño de petición.

## Alternativas

| Opción | Qué cubre | Coste y riesgo en este VPS |
| --- | --- | --- |
| Coraza + OWASP CRS en Caddy | Inspección de solicitudes y reglas conocidas de inyección/escaneo antes de nginx y Java | Requiere imagen personalizada de Caddy, memoria y CPU por petición, ajuste de falsos positivos y política de actualización de CRS. El consumo real en este VPS **no se midió**. |
| CrowdSec/Fail2ban con logs de Caddy | Bloqueo temporal de IPs que insisten; útil para escaneos repetidos | Requiere colector, reglas y aplicación de decisiones; no inspecciona cada payload como CRS. Fail2ban ya protege SSH, no las rutas HTTP. La IP debe salir de Caddy, no de cabeceras aportadas por el cliente. |
| Reglas y límites en gateway | Límites de tamaño, método, rutas y tasas específicos por endpoint, sin otra pieza de infraestructura | El tráfico ya llegó al proceso Java; un filtro casero de SQL/XSS puede bloquear JSON legítimo y no reemplaza un WAF. Es la mejora más pequeña para abusos concretos. |
| WAF externo (Cloudflare) | Filtrado perimetral y reglas administradas antes de consumir recursos del VPS | Depende de DNS, plan, proveedor y disponibilidad; habría que evitar acceso directo al origen y configurar excepciones para callbacks. No se midió el precio ni la latencia para este dominio. |

## Compatibilidad con integraciones

Los callbacks de Khipu y Mercado Pago, el retorno de Transbank, la carga
de cartera y el chat pueden contener JSON, firmas, tokens y texto libre.
No se debe exceptuar **todo** `/api/payments` ni permitir todas las IP de una
pasarela: usar detección inicial sin bloqueo, registrar la regla que disparó,
reproducir solicitudes válidas y ajustar solo el identificador de regla y la
ruta afectados. Las firmas y la idempotencia del servicio siguen siendo
obligatorias. También hay que probar cuerpos grandes permitidos para cartera,
cabeceras de firmas y respuestas de 401/403/429 legítimas.

## Decisión y comprobaciones pendientes

**No activar todavía un WAF en modo bloqueo en el VPS.** El sistema ya tiene
límites y validaciones por servicio; sin una línea base de memoria/latencia ni
tráfico representativo, añadir CRS al mismo host pequeño puede causar más
interrupciones que beneficio. Primero:

1. Documentar rutas y límites de cuerpo concretos, y medir con las pruebas
   existentes de `performance-tests/k6/` contra Caddy (p95/p99, CPU, RSS y
   rechazos por ruta), sin credenciales ni pasarelas reales.
2. Si hay escaneos repetidos, evaluar CrowdSec sobre logs de Caddy en modo de
   observación; si predominan payloads maliciosos, probar Coraza + CRS en un
   entorno aislado **sin bloquear**, con corpus de callbacks válidos.
3. Comparar p95/p99, memoria pico y falsos positivos antes y después. Solo
   habilitar bloqueo por reglas evaluadas, con exclusión por regla/ruta,
   registro de decisiones y rollback a la imagen previa de Caddy.

No se hicieron pruebas en vivo ni se afirma una cifra de consumo para este
servidor. Cualquier despliegue de WAF será un issue `security(...)` separado,
con presupuesto de recursos y prueba de los callbacks.

## Fuentes

- [Coraza para Caddy](https://github.com/corazawaf/coraza-caddy) y
  [guía del OWASP Core Rule Set](https://coreruleset.org/docs/).
- [CrowdSec: listas de exclusión](https://docs.crowdsec.net/u/getting_started/post_installation/whitelists/)
  y [componentes de bloqueo](https://docs.crowdsec.net/u/user_guides/bouncers_configuration/).
- [Cloudflare: reglas administradas](https://developers.cloudflare.com/waf/managed-rules/)
  y [excepciones](https://developers.cloudflare.com/waf/managed-rules/waf-exceptions/).
- Configuración propia: `deploy/Caddyfile`, `deploy/preparar-servidor.sh`,
  `gateway/src/main/java/com/tbridge/gateway/filter/RateLimitFilter.java`.
