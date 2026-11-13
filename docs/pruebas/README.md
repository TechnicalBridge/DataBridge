## 11. Pruebas

```powershell
.\mvnw.cmd -f ms-debt\pom.xml clean test                        # un servicio (lo mismo con gateway, ms-auth, ms-payments o ms-ai)
foreach ($s in "gateway","ms-auth","ms-debt","ms-payments","ms-ai") { .\mvnw.cmd -q -f "$s\pom.xml" clean test }   # los cinco
```

En GitHub cada módulo tiene su propio workflow (`gateway`, `ms-auth`, `ms-debt`, `ms-payments`,
`frontend`, `ms-ai`), que prueba solo si cambió su carpeta y deja en el resumen cuántas pruebas
pasaron, clase por clase. Los de Java comparten [`java.yml`](../../.github/workflows/java.yml). Todos
con permisos de solo lectura y con cada acción externa fijada a un commit exacto.

**`main` está protegida.** Nadie sube directo: todo entra por un PR, se mergea con squash y solo si
pasan los seis checks `CI de <módulo>`. En un PR cada workflow corre siempre, pero si su carpeta no
cambió se salta las pruebas y su check pasa en segundos. Así un PR que solo cambia el README no
espera pruebas que nunca van a correr.

Cada servicio tiene sus pruebas en `src/test/java`, con la misma estructura de paquetes que el
código. **Ninguna necesita base de datos ni internet**, así que corren en segundos en cualquier
equipo: Transbank, Khipu, Mercado Pago, Brevo y el LLM se reemplazan por un servidor HTTP local.

> Usa `clean`. El editor de VS Code compila por su cuenta dentro de `target/`, y sin `clean`
> Maven puede dar por buena una clase vieja.

**1.359 pruebas**, todas en Java y sin fallos (con JDK 25 y `clean test` en cada servicio, contando los XML de Surefire sobre `main` `f60c5c2`, 12 de noviembre de 2026):

| Módulo | Pruebas |
| --- | --- |
| `gateway` | 29 |
| `ms-auth` | 322 |
| `ms-debt` | 541 |
| `ms-payments` | 303 |
| `ms-ai` | 164 |

En #228 se repitieron las cinco suites Java (1.305 pruebas, cero fallos, errores u
omisiones). Los 46 casos añadidos cubrían configuración y contratos HTTP de Groq,
Gemini y xAI (quitado después, en #312) con servidores simulados. El portal compiló con `npm ci` y
`npm run build`. En un proyecto Docker aislado se verificaron Groq y Gemini
ficticios por portal/nginx → gateway → ms-ai, source externo, fallback local,
rechazos 401/403 y separación A/B. El chat del portal mostró la respuesta del
stub; no se consumió un proveedor real ni se modificó el stack del usuario.
Esta verificación usa deuda/JWKS simulados; no certifica el E2E con ms-debt real.

En #221 se ejecutaron las cinco suites Java: ms-ai 130, gateway 20, ms-auth 315,
ms-debt 513 y ms-payments 281; cero fallos, errores u omisiones, contados desde
Surefire. El portal también compiló con `npm ci` y `npm run build`. La integración
HTTP local usa JWT/JWKS efímeros y dependencias ficticias, sin proveedor LLM real.
El E2E Docker de #221 está pendiente: daemon local no disponible; no se declara PASS.

El asistente valida su sesión ES256 mediante `AUTH_JWKS_URI` antes de consultar
ms-debt. Solo `ROLE_DEBTOR` puede usar `POST /api/ai/chat`; health y documentación
conservan acceso público según su configuración. Reenvía el token validado original
para una segunda verificación independiente en deuda, sin claves privadas ni clave interna.
El fallback local solo ocurre después de obtener contexto autorizado y válido.

| Resultado del asistente | HTTP |
| --- | --- |
| Sesión ausente, inválida o caducada | 401 |
| Rol no autorizado o rechazo de permisos de deuda | 403 |
| Identidad no registrada, reconocida por el contrato de deuda | 404 |
| Colección de deudas vacía legítima | 200 |
| Protocolo de deuda inválido o ruta upstream desconocida | 502 |
| Deuda o verificación JWKS temporalmente indisponible | 503 |
| Proveedor LLM ausente o fallido, con contexto ya autorizado | 200 mediante motor local |

Los errores son `ApiError`, nunca una respuesta financiera vacía. No se registran
bodies upstream, credenciales ni contexto conversacional en los errores de AI.

Además, `pruebas/secretos.test.cjs` (Node, 28 pruebas) comprueba que las reglas de gitleaks detecten
cada tipo de clave y dejen pasar los valores públicos ([`docs/seguridad/secretos.md`](../seguridad/secretos.md)).
La cadena completa con los tres sistemas se prueba de punta a punta con un script que
vive fuera de este repositorio, en la carpeta que reúne a los tres
([estado](../metodologia/estado-proyecto.md)).

<details>
<summary><b>Qué cubre cada tipo de prueba</b></summary>

| Tipo | Qué cubre |
| --- | --- |
| **Del modelo de negocio** | Los cuatro tramos, los límites y el redondeo en pesos; el total mensual de todas las campañas, con la zona horaria de Chile; los pagos en UF con sus pesos guardados; los meses vacíos, el aislamiento de la empresa y la vista del admin. Un detalle antiguo nulo o mal formado no impide calcular un pago en pesos |
| **Unitarias** (JUnit 5 + Mockito) | Las reglas de cada servicio con los repositorios simulados. Que cada quien vea solo lo suyo; que el monto salga de ms-debt y no del navegador; que un pago avisado dos veces se abone una; que las cuotas se paguen en orden; que solo entren deudores morosos; que un cliente al día cierre lo que estaba en cobranza; que el mandato registre al acreedor nuevo; que un convenio sobreviva al mes siguiente; el reclamo y sus dos resoluciones; el cifrado y que un secreto viejo se cifre al arrancar; la sesión revocable, el código de acceso, la UF, la firma de los eventos y el recordatorio |
| **De Webpay** | El cliente contra un Transbank falso: crear, confirmar, consultar y sus errores, sin que el detalle de Transbank ni la llave lleguen a la persona; el plazo según el ambiente. El retorno aprobado; rechazado, sin código, con otro monto o con otra orden; anulado, con error de formulario y por tiempo; repetido; con Transbank caído; con un token ajeno. La página que manda el token una sola vez. La consulta periódica: el pago sin confirmar que se confirma, el confirmado que se registra, el fallido, reversado o anulado, el que sigue abierto, el que vence pasado el plazo y el que vence si Transbank no contesta en un día. Al abrir otro cobro, el anterior pagado se registra y frena el nuevo, y el anterior sin pagar se vence |
| **De seguridad de las pasarelas** | La página de Webpay escapa lo que muestra; con la firma de otro pago no se abre ni se gasta el token; una vuelta por tiempo con una sesión inventada no hace fallar el cobro de otro; la respuesta del cobro no muestra el token; un cobro vencido que se pagó no se confirma (para no cobrar dos veces); la consulta no acepta otro monto ni otra orden; la vuelta, la página y la consulta bloquean la fila del pago, y entre la consulta y la vuelta se confirma una sola vez |
| **De las pasarelas lentas o caídas** | Webpay, Khipu, Mercado Pago y ms-debt, contra un servidor que tarda más que el tiempo máximo y contra un puerto donde no escucha nadie: cada cliente corta en menos de dos segundos, sin mostrar el detalle técnico |
| **De Mercado Pago** | El cliente contra un Mercado Pago falso: la preferencia con el token, la vuelta (con `auto_return` solo si es https) y el `init_point`, y sus errores; la preferencia vence a la hora pedida. La conciliación por las órdenes de la preferencia, también la periódica que vence lo abandonado: sin pagos, con un pago aprobado (también después de una tarjeta rechazada), con una tarjeta rechazada que deja el cobro abierto para reintentar, con Mercado Pago caído o con una preferencia que no existe; la vuelta y el aviso, sin sesión |
| **De la confirmación de Mercado Pago** | Retorno firmado; sin firma no revela el enlace ni cambia el cobro. Retorno y aviso rechazan monto, moneda, referencia, identificador o pasarela ajenos. Solo abona el pago de la preferencia guardada; admite reintentos, avisos repetidos y aprobaciones posteriores al vencimiento. Una caída no aprueba pagos y el aviso devuelve error para reintentarlo |
| **De Khipu** | El cliente contra un Khipu falso: crear el cobro, preguntar en qué va y sus errores. El pago conciliado, el que sigue en verificación, el rechazado, el revertido y el arrepentido (con el cobro anulado en Khipu, o pagado justo antes de anularlo); que otro monto u otra transacción no se aprueben; que un pago real no se confirme por la simulación; la consulta periódica que cierra lo pagado y vence lo abandonado, y la firma de los avisos |
| **Del doble pago** | Un segundo pago mientras otro se verifica se rechaza; el abierto sin pagar se anula (Khipu lo borra, la preferencia de Mercado Pago vence en el acto) y queda vencido; una cuota pagada que ms-debt todavía no abona no se cobra de nuevo; el cobro guarda las cuotas que cubre, y ms-debt las informa en orden. Si igual se paga dos veces, el segundo queda `duplicated`, sin abonar y con la referencia y el RUT para devolverlo; pagar otras cuotas de la misma deuda no es duplicado. La consulta periódica no vence lo que Khipu o Mercado Pago están verificando y anula en Khipu lo que vence; un vencido que se paga después se registra, por la revisión de vencidos o por el aviso de Khipu |
| **De los intereses** | La mora de cada cargo atrasado desde el día siguiente a su vencimiento, con lo pagado imputado a lo más antiguo; la cuota del convenio que crece solo sobre su capital; el redondeo en pesos y en UF; el convenio en sistema francés, con la última cuota que absorbe el redondeo; que sin tasa todo quede como antes; que el cobro fije el capital y el interés y el aviso los lleve separados; la tasa que no es un número o que supera el tope |
| **De las campañas** | Que cada recordatorio salga el día que dice la cadencia y no antes; los intentos, las fechas y el estado de la campaña; que pare con el pago, el convenio, el reclamo o el retiro; el horario, el domingo y el feriado; los feriados calculados contra los publicados (2023, 2026 y 2027 completos, y cada regla contra un año real: el 2 de enero, el 17 y el 20 de septiembre, los que se corren al lunes, el 31 de octubre y el solsticio al minuto); el límite de dos por semana con dos días entre uno y otro, y el horario de la ley, que frenan también la invitación, el recordatorio de cuota y el reenvío del código; el estado que manda APOFYX; el acreedor que cobra sin agencia: registra, pausa y termina su campaña sin mandato, no puede registrar la de otro, sus carteras (JSON y planilla) la nombran en el lote, y el avance le llega a él; desde el portal, solo una empresa ve y cambia sus campañas, sin un id DataBridge le pone uno, cambiar el estado no pisa el nombre ni el inicio, y se rechazan la cadencia que no crece o trae ceros, los intentos fuera de 1 a 10, el fin antes del inicio y un id de más de 64 caracteres |
| **Del descuento por pronto pago** | El tramo por los días del cargo impago más antiguo (30, 31, 62, 100 y 150 días); el % de la campaña recortado al máximo vigente del mandato, que baja si el acreedor lo baja; sin máximo, con el mandato vencido, con la campaña pausada o fuera de sus fechas, nada; el acreedor sin agencia se autoriza hasta 100. El mandato guarda su máximo y lo cambia al reenviarse; la campaña rechaza un tramo que no existe o un valor fuera de 0 a 100 (`descuento_invalido`) y uno sobre el máximo (`descuento_sobre_tope`), y `{}` le quita el descuento. Al cobrar: el ejemplo del contrato ($900.000 + $18.800 − $9.400 = $909.400), sin descuento en un pago de parte de la deuda ni en convenio, la oferta que ve el deudor con su fecha, el descuento fijo en ms-payments desde que se abre el cobro, y el aviso que lo lleva hasta el acreedor, también cuando se condona toda la mora |
| **Del asistente** | Las reglas: los montos en pesos y en UF sin sumarlos entre sí, el ánimo (la desconfianza pesa más que la cortesía) y el tono de cada respuesta. Lo que dice de la plata, igual que el portal: el total de hoy con la mora, la tasa que pactó el acreedor (también en las cuotas), que sin tasa no hay interés, y el descuento por pronto pago con su fecha. Groq y Gemini se prueban con servidores HTTP ficticios: una sola llamada a Chat Completions, source correcto, health sin secretos y categorías de error sanitizadas. Si el proveedor falla o contesta en blanco, responde con las reglas solo después de obtener contexto autorizado; viajan los últimos doce mensajes. Los errores de sesión o deuda conservan 401/403/404/502/503 y nunca se convierten en éxito vacío. Arranca solo y publica su salud y su Swagger |
| **De los roles** | El `admin` de DataBridge entra con su propio rol y sin empresa, y el usuario de una empresa sigue entrando como antes; una llave de empresa no renueva como `admin`. El primer `admin` sale de `ADMIN_CORREO` una sola vez, y a una persona de una empresa no se le da ese rol. En los tres servicios, el panel responde al `admin`, `403` a una empresa y a un deudor, y `401` sin sesión, también en una ruta que todavía no existe; y el `admin` recibe `403` en las deudas y en los pagos. En el gateway, cada tramo del panel llega a su servicio y `/internal` sigue sin puerta |
| **Del alta de empresas y personas** | Los totales de una empresa cuentan lo suyo como acreedora y lo que entregó como agencia, sin mezclar pesos con UF y sin leer una deuda. Una empresa nueva pide un RUT válido y que no exista. Una persona creada bajo una empresa queda como `usuario`, aunque el cuerpo pida otro rol, y un correo de una empresa no puede pasar a `admin`. Sin la empresa en ms-debt, o si ms-debt no responde, no se crea a nadie. Quitar el acceso cierra las sesiones de esa persona y anula sus enlaces sin abrir, y un `admin` no se lo puede quitar a sí mismo. Cada alta y cada cambio quedan anotados con quién los hizo |
| **Del correo** | Brevo contra un servidor falso: lo que pide su API, con la clave en su cabecera y nunca en un error. De quién es la culpa de un correo que no sale: la dirección (rechazada o mal escrita) o el servicio (una clave mala, el cupo, una IP bloqueada, un servidor que no responde, un remitente mal configurado). El modo de prueba: lo que se desvía, el alias de la cuenta del equipo, y que lo que no deja salir no es un fallo. Cada correo en texto y en HTML; el del deudor trae el código y el acreedor pero ningún enlace, ni el monto; el nombre del acreedor no mete HTML; el adelanto del buzón no trae el código. El correo de prueba dice adónde llegó o por qué no |
| **De la carga por campaña** | Sin campaña no entra nada ni se revisa; en una campaña terminada tampoco. La planilla entra en su campaña, con el lote y el corte armados solos o con los que manda el portal. La revisión deshace siempre lo que hizo y dice cuánto entraría por moneda, también cuando la ingesta rechaza el lote |
| **De la planilla de Excel** | Sobre la plantilla de verdad, con celdas de su tipo: una persona que debe tres meses es una deuda con tres cargos; el RUT con o sin puntos; UF con decimales y CLP entero, exactos; el cliente al día sin cargos y el retiro con su motivo; meses, fechas y montos escritos como texto (`jul-2026`, `350.000`). Cada problema con su hoja, fila y columna, y todos juntos: un RUT que no está en *Deudores*, un mes, una fecha o un monto mal escritos, una deuda que cambia de moneda entre meses, un mes repetido, un CLP con decimales, un monto en cero, un al día con meses, un retiro sin motivo, un deudor repetido o sin correo. La plantilla trae sus tres hojas, las listas, los tipos y la mora mínima vigente; solo una empresa la descarga, y los problemas salen en `error.detalles` |
| **De la entrega por API apagada** | Sin `INTEGRACION_API`, `/api/v1` y `/api/claves` responden `404` sin mirar la clave, y Swagger muestra el contrato vacío y por qué. Con `INTEGRACION_API=true`, el contrato y las claves funcionan como antes |
| **De Swagger** | Cada grupo ofrece en *Authorize* solo la llave que usan sus operaciones, sin quitársela a los otros grupos; lo público se llama por la dirección desde la que se abrió Swagger, y lo interno dice que con Docker no se alcanza desde el navegador y cómo llamarlo. ms-ai solo publica rutas que el gateway le manda (`/api/ai/**`) |
| **De las reglas del panel** | Cada regla dice cuánto vale y de qué variable sale, o que la fija la ley; cómo entran los deudores; el horario apagado para probar, a gritos; y los feriados, los mismos que usan las campañas |
| **De la capa web** (`@WebMvcTest` + MockMvc) | Cada controlador con su seguridad, su validación y su JSON: `401` sin sesión, `403` con la deuda de otro, `400` con datos malos, los `_links` según quién mira, la cookie de la sesión y los nombres del contrato v1 intactos. El aviso de Khipu llega con el cuerpo tal como vino, porque sobre ese texto va la firma. En el gateway, que el retorno de Webpay pase sin `Origin` y nada más |
| **Del contrato del aviso de pago** | ms-payments manda exactamente el ejemplo del contrato ([`docs/eventos`](../eventos/README.md); cada uno guarda una copia en sus pruebas, y la CI revisa que sean iguales), por el exchange y la clave que dice; ms-debt escucha en esa cola y entiende cada campo; un mensaje no puede elegir qué clase se crea al leerlo |
| **De seguridad** | **CodeQL** (Java y JavaScript) en cada pull request y cada semana; la auditoría de dependencias del portal, que rompe su workflow ante una vulnerabilidad alta; Dependabot por módulo, también para las acciones de GitHub; y el escaneo de secretos de GitHub con protección de push, que rechaza un push que traiga un token conocido |
| **De rendimiento** (k6) | Cómo lo siente una persona, dónde está el techo y cuánto aguanta abrir cobros (`pagos.js`, solo con las pasarelas simuladas). Se corren a mano, con el sistema arriba ([`docs/rendimiento`](../rendimiento/README.md)) |

</details>

---
