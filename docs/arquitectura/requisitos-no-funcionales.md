## 9. Requisitos no funcionales

**Seguridad**

| Requisito | C├│mo se cumple | D├│nde est├í |
| --- | --- | --- |
| **Autenticaci├│n** | Los tres portales entran por c├│digo: RUT del deudor o correo del personal. Un solo uso, 5 intentos; 15 min si se solicita en el portal y 24 h si viene de campa├▒a. El alfabeto excluye `0 O 1 I L`. Usar el c├│digo o el enlace del respaldo consume ambos, incluso con solicitudes simult├íneas | `ms-auth/AuthService`, `V6__codigo_y_enlace_del_mismo_acceso.sql` |
| **Sesi├│n** | El JWT dura **15 minutos** y vive en la memoria de la pesta├▒a, no en `localStorage`. Mantiene a la persona adentro una llave de renovaci├│n de 256 bits en una cookie `HttpOnly` y `SameSite=Strict`, que ning├║n script puede leer. Cada uso la cambia por otra; cerrar sesi├│n la revoca **en el servidor**, y si una llave usada reaparece, se revoca toda la sesi├│n | `ms-auth/SessionService`, `V2__sesiones.sql` |
| **Origen** | Cada freno cuenta por IP, as├¡ que la IP no se puede inventar: nginx sobrescribe `X-Forwarded-For`, y el gateway solo les cree a sus proxies (`TRUSTED_PROXIES`). CORS acepta solo los or├¡genes del portal (`CORS_ORIGINS`), no `*` | `frontend/nginx.conf.template`, `gateway/ClienteReal` |
| **Secretos** | Lo que solo se compara se guarda como hash: c├│digos, tokens de enlace, llaves de renovaci├│n y claves de API. Lo que hay que leer de vuelta ΓÇöel secreto con que se firman los avisosΓÇö va **cifrado con AES-256-GCM**, con una llave fuera de la base (`CIFRADO_LLAVE`). Un secreto en claro de antes se cifra solo al arrancar. **Ninguna clave llega al repositorio**: GitHub bloquea el push de los proveedores que conoce, y gitleaks revisa los commits nuevos de cada PR con las reglas de DataBridge | `ms-debt/config/Cifrado`, `V3__secretos_cifrados.sql`, `.gitleaks.toml`, [`docs/seguridad/secretos.md`](docs/seguridad/secretos.md) |
| **Pagos** | Nada de lo que traiga el navegador se aplica: ms-payments le pregunta a la pasarela. Un pago de Webpay se aprueba solo si Transbank confirma `AUTHORIZED` con c├│digo 0 **y** el monto y la orden calzan; un retorno repetido no se vuelve a confirmar. Uno de Khipu, solo si Khipu dice que est├í conciliado (`done`, sin reversa) **y** el monto y la transacci├│n calzan; su aviso se verifica con firma HMAC. Un pago real no se puede confirmar por el camino de la simulaci├│n | `ms-payments/PaymentService`, `WebpayClient`, `KhipuClient`, `FirmaDeKhipu` |
| **Enlace** | El respaldo re├║ne c├│digo y enlace en un ├║nico correo, **solo al correo registrado por el acreedor** para ese RUT; el deudor nunca elige otro destino. Al personal le llega solo si est├í habilitado. Un acceso pedido con RUT abre solo la sesi├│n de ese deudor; `sent` indica que se agend├│ el env├¡o, no que se entreg├│ | `AuthService.pedirEnlace`, `DeudasClient`, `V6__codigo_y_enlace_del_mismo_acceso.sql` |
| **Enumeraci├│n** | Los pedidos de c├│digo y respaldo responden el mismo `202` exista o no la identidad. El env├¡o es as├¡ncrono y la respuesta no contiene credenciales. No se garantiza tiempo constante: resolver el destinatario requiere consultar ms-debt o la base local, seg├║n la identidad | `AuthService.pedirCodigo`, `AuthService.pedirEnlace` |
| **Autorizaci├│n** | Cada sesi├│n se identifica por RUT. Un deudor ve, paga y reclama solo lo suyo; una agencia ve y resuelve solo la cartera de su mandato | `DebtService`, `DisputaService` |
| **Integridad** | Los eventos van firmados con HMAC-SHA256, caducan a los 5 minutos y se descartan si llegan repetidos | `docs/integracion/README.md` ┬º8 |
| **Superficie** | De la aplicaci├│n, **solo el portal publica un puerto**. Las tres bases, RabbitMQ y el buz├│n publican el suyo **a prop├│sito**, para revisarlos en desarrollo; en un despliegue real esas l├¡neas `ports:` se borran | `docker-compose.yml` |
| **Contenedores** | Ninguna imagen corre como root: los servicios usan el usuario `10001` y el portal la nginx sin privilegios. Las im├ígenes de Java llevan solo el JRE, sin compilador ni c├│digo fuente | `*/Dockerfile` |
| **Entradas** | Cada petici├│n entra como un tipo con sus reglas (`@NotBlank`, `@Size`, `@Pattern`...), y lo que no cumple se responde con `400` y un mensaje para la persona. Un JSON mal escrito o una ruta que no existe responden `400` y `404`, no `500` | `dto/request/`, `exception/ApiExceptionHandler` de cada servicio |

**Rendimiento**

| Requisito | C├│mo se cumple | D├│nde est├í |
| --- | --- | --- |
| **Medido** | Con 100 personas a la vez, **cero errores** en unas 32.000 peticiones y un p95 de 7 a 12 ms seg├║n el d├¡a. En estr├⌐s, holgado hasta 1.000 peticiones por segundo; **toca techo en unas 1.800**, donde se pone lento pero no falla. Mediciones del 23 y 24 de septiembre, con m├íquina y detalle en [`docs/rendimiento`](docs/rendimiento/README.md) | `rendimiento/` |
| **L├¡mite de peticiones** | Dos capas: el gateway deja 10 por minuto en `/api/auth/**` y 120 globales por IP, y `ms-auth` bloquea diez minutos al origen que falla diez c├│digos | `gateway/RateLimitFilter`, `AuthService` |
| **Consultas** | ├ìndices para los caminos que se usan: `ix_debt_creditor_status` (la cartera de un acreedor), `ix_debt_debtor` (lo que debe una persona). `uq_payment_gateway` evita cobrar dos veces la misma transacci├│n y adem├ís es el ├¡ndice con que se busca el retorno de Webpay y el aviso de Khipu | `V1__esquema_inicial.sql` |
| **Memoria** | La JVM lee el l├¡mite del contenedor (`MaxRAMPercentage=75`), y cada servicio tiene su tope declarado | `*/Dockerfile` |
| **Portal** | La p├ígina de la empresa, la de los gr├íficos, se carga de forma diferida: el JS principal pesa 364 kB y el de esa p├ígina 420 kB, antes de gzip (115 kB cada uno con gzip) | `frontend/src/App.jsx` |

**Privacidad, disponibilidad, escalabilidad y mantenci├│n**

| Requisito | C├│mo se cumple | D├│nde est├í |
| --- | --- | --- |
| **Privacidad** | Ning├║n evento lleva datos personales del deudor. El texto que escribe al reclamar se queda en DataBridge: a la cadena viaja solo el motivo | `EventosService`, decisi├│n I5 del contrato |
| **Usabilidad** | Tema claro (crema y verde bosque) y oscuro (el mismo verde sobre carb├│n: una sola marca), que sigue al del sistema hasta que la persona elige. Quien pidi├│ menos movimiento no ve animaciones. La barra de estado se anuncia como barra de progreso a los lectores de pantalla. El texto llega a 4,5:1 de contraste en los dos temas, y los t├¡tulos van en orden, h1, h2 y h3, sin saltos ([#301](https://github.com/TechnicalBridge/DataBridge/issues/301)) | `frontend/src/index.css` |
| **Disponibilidad** | Los once contenedores declaran `healthcheck`, y ninguno arranca antes que aquel del que depende. La salud la da Actuator, que incluye la conexi├│n a la base. `restart: unless-stopped` los repone si se caen | `docker-compose.yml` |
| **Disponibilidad** ┬╖ entrega | Todo lo que sale hacia otro sistema pasa por una bandeja con reintentos (1 min, 5 min, 30 min, 2 h, 6 h, 24 h). Si APOFYX est├í ca├¡do, el aviso se entrega cuando vuelve | `outbox`, `EventDispatcher` |
| **Escalabilidad** | La identidad viaja en JWT y las sesiones se guardan en `tb_auth`. Para varias r├⌐plicas falta coordinar dos cosas: los l├¡mites del gateway viven en la memoria de cada instancia, y las tareas programadas (recordatorios, despachadores, vencimiento de pagos) correr├¡an en cada una. No se valid├│ con varias instancias | `RateLimitFilter`, `EventDispatcher`, `ConciliacionPasarelas`, `WebpayVencidos` |
| **Portabilidad** | Una orden levanta el sistema entero en cualquier m├íquina con Docker | `docker-compose.yml` |
| **Mantenibilidad** | Los tres servicios tienen la misma estructura de paquetes. `ddl-auto: validate` se niega a arrancar si las entidades y las tablas no calzan | `application.properties` |
| **Documentaci├│n** | Todos los endpoints en Swagger, con sus respuestas posibles y ejemplos reales | http://localhost:8080/swagger-ui.html |

---

