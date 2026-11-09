## 11. Pruebas

```powershell
.\mvnw.cmd -f ms-debt\pom.xml clean test                        # un servicio (lo mismo con gateway, ms-auth, ms-payments o ms-ai)
foreach ($s in "gateway","ms-auth","ms-debt","ms-payments","ms-ai") { .\mvnw.cmd -q -f "$s\pom.xml" clean test }   # los cinco
```

En GitHub cada m├│dulo tiene su propio workflow (`gateway`, `ms-auth`, `ms-debt`, `ms-payments`,
`frontend`, `ms-ai`), que prueba solo si cambi├│ su carpeta y deja en el resumen cu├íntas pruebas
pasaron, clase por clase. Los de Java comparten [`java.yml`](.github/workflows/java.yml). Todos
con permisos de solo lectura y con cada acci├│n externa fijada a un commit exacto.

**`main` est├í protegida.** Nadie sube directo: todo entra por un PR, se mergea con squash y solo si
pasan los seis checks `CI de <m├│dulo>`. En un PR cada workflow corre siempre, pero si su carpeta no
cambi├│ se salta las pruebas y su check pasa en segundos. As├¡ un PR que solo cambia el README no
espera pruebas que nunca van a correr.

Cada servicio tiene sus pruebas en `src/test/java`, con la misma estructura de paquetes que el
c├│digo. **Ninguna necesita base de datos ni internet**, as├¡ que corren en segundos en cualquier
equipo: Transbank, Khipu, Mercado Pago, Brevo y el LLM se reemplazan por un servidor HTTP local.

> Usa `clean`. El editor de VS Code compila por su cuenta dentro de `target/`, y sin `clean`
> Maven puede dar por buena una clase vieja.

**1320 pruebas**, todas en Java y sin fallos (con JDK 25; las cinco suites, medidas de nuevo con #312):

| M├│dulo | Pruebas |
| --- | --- |
| `gateway` | 25 |
| `ms-auth` | 319 |
| `ms-debt` | 532 |
| `ms-payments` | 281 |
| `ms-ai` | 163 |

En #228 se repitieron las cinco suites Java (1.305 pruebas, cero fallos, errores u
omisiones). Los 46 casos a├▒adidos cubr├¡an configuraci├│n y contratos HTTP de Groq,
Gemini y xAI (quitado despu├⌐s, en #312) con servidores simulados. El portal compil├│ con `npm ci` y
`npm run build`. En un proyecto Docker aislado se verificaron Groq y Gemini
ficticios por portal/nginx ΓåÆ gateway ΓåÆ ms-ai, source externo, fallback local,
rechazos 401/403 y separaci├│n A/B. El chat del portal mostr├│ la respuesta del
stub; no se consumi├│ un proveedor real ni se modific├│ el stack del usuario.
Esta verificaci├│n usa deuda/JWKS simulados; no certifica el E2E con ms-debt real.

En #221 se ejecutaron las cinco suites Java: ms-ai 130, gateway 20, ms-auth 315,
ms-debt 513 y ms-payments 281; cero fallos, errores u omisiones, contados desde
Surefire. El portal tambi├⌐n compil├│ con `npm ci` y `npm run build`. La integraci├│n
HTTP local usa JWT/JWKS ef├¡meros y dependencias ficticias, sin proveedor LLM real.
El E2E Docker de #221 est├í pendiente: daemon local no disponible; no se declara PASS.

El asistente valida su sesi├│n ES256 mediante `AUTH_JWKS_URI` antes de consultar
ms-debt. Solo `ROLE_DEBTOR` puede usar `POST /api/ai/chat`; health y documentaci├│n
conservan acceso p├║blico seg├║n su configuraci├│n. Reenv├¡a el token validado original
para una segunda verificaci├│n independiente en deuda, sin claves privadas ni clave interna.
El fallback local solo ocurre despu├⌐s de obtener contexto autorizado y v├ílido.

| Resultado del asistente | HTTP |
| --- | --- |
| Sesi├│n ausente, inv├ílida o caducada | 401 |
| Rol no autorizado o rechazo de permisos de deuda | 403 |
| Identidad no registrada, reconocida por el contrato de deuda | 404 |
| Colecci├│n de deudas vac├¡a leg├¡tima | 200 |
| Protocolo de deuda inv├ílido o ruta upstream desconocida | 502 |
| Deuda o verificaci├│n JWKS temporalmente indisponible | 503 |
| Proveedor LLM ausente o fallido, con contexto ya autorizado | 200 mediante motor local |

Los errores son `ApiError`, nunca una respuesta financiera vac├¡a. No se registran
bodies upstream, credenciales ni contexto conversacional en los errores de AI.

Adem├ís, `pruebas/secretos.test.cjs` (Node, 28 pruebas) comprueba que las reglas de gitleaks detecten
cada tipo de clave y dejen pasar los valores p├║blicos ([`docs/seguridad/secretos.md`](docs/seguridad/secretos.md)).
La cadena completa con los tres sistemas se prueba de punta a punta con un script que
vive fuera de este repositorio, en la carpeta que re├║ne a los tres
([estado](#14-estado-al-19-de-octubre-de-2026)).

<details>
<summary><b>Qu├⌐ cubre cada tipo de prueba</b></summary>

| Tipo | Qu├⌐ cubre |
| --- | --- |
| **Del modelo de negocio** | Los cuatro tramos, los l├¡mites y el redondeo en pesos; el total mensual de todas las campa├▒as, con la zona horaria de Chile; los pagos en UF con sus pesos guardados; los meses vac├¡os, el aislamiento de la empresa y la vista del admin. Un detalle antiguo nulo o mal formado no impide calcular un pago en pesos |
| **Unitarias** (JUnit 5 + Mockito) | Las reglas de cada servicio con los repositorios simulados. Que cada quien vea solo lo suyo; que el monto salga de ms-debt y no del navegador; que un pago avisado dos veces se abone una; que las cuotas se paguen en orden; que solo entren deudores morosos; que un cliente al d├¡a cierre lo que estaba en cobranza; que el mandato registre al acreedor nuevo; que un convenio sobreviva al mes siguiente; el reclamo y sus dos resoluciones; el cifrado y que un secreto viejo se cifre al arrancar; la sesi├│n revocable, el c├│digo de acceso, la UF, la firma de los eventos y el recordatorio |
| **De Webpay** | El cliente contra un Transbank falso: crear, confirmar, consultar y sus errores, sin que el detalle de Transbank ni la llave lleguen a la persona; el plazo seg├║n el ambiente. El retorno aprobado; rechazado, sin c├│digo, con otro monto o con otra orden; anulado, con error de formulario y por tiempo; repetido; con Transbank ca├¡do; con un token ajeno. La p├ígina que manda el token una sola vez. La consulta peri├│dica: el pago sin confirmar que se confirma, el confirmado que se registra, el fallido, reversado o anulado, el que sigue abierto, el que vence pasado el plazo y el que vence si Transbank no contesta en un d├¡a. Al abrir otro cobro, el anterior pagado se registra y frena el nuevo, y el anterior sin pagar se vence |
| **De seguridad de las pasarelas** | La p├ígina de Webpay escapa lo que muestra; con la firma de otro pago no se abre ni se gasta el token; una vuelta por tiempo con una sesi├│n inventada no hace fallar el cobro de otro; la respuesta del cobro no muestra el token; un cobro vencido que se pag├│ no se confirma (para no cobrar dos veces); la consulta no acepta otro monto ni otra orden; la vuelta, la p├ígina y la consulta bloquean la fila del pago, y entre la consulta y la vuelta se confirma una sola vez |
| **De las pasarelas lentas o ca├¡das** | Webpay, Khipu, Mercado Pago y ms-debt, contra un servidor que tarda m├ís que el tiempo m├íximo y contra un puerto donde no escucha nadie: cada cliente corta en menos de dos segundos, sin mostrar el detalle t├⌐cnico |
| **De Mercado Pago** | El cliente contra un Mercado Pago falso: la preferencia con el token, la vuelta (con `auto_return` solo si es https) y el `init_point`, y sus errores; la preferencia vence a la hora pedida. La conciliaci├│n por las ├│rdenes de la preferencia, tambi├⌐n la peri├│dica que vence lo abandonado: sin pagos, con un pago aprobado (tambi├⌐n despu├⌐s de una tarjeta rechazada), con una tarjeta rechazada que deja el cobro abierto para reintentar, con Mercado Pago ca├¡do o con una preferencia que no existe; la vuelta y el aviso, sin sesi├│n |
| **De la confirmaci├│n de Mercado Pago** | Retorno firmado; sin firma no revela el enlace ni cambia el cobro. Retorno y aviso rechazan monto, moneda, referencia, identificador o pasarela ajenos. Solo abona el pago de la preferencia guardada; admite reintentos, avisos repetidos y aprobaciones posteriores al vencimiento. Una ca├¡da no aprueba pagos y el aviso devuelve error para reintentarlo |
| **De Khipu** | El cliente contra un Khipu falso: crear el cobro, preguntar en qu├⌐ va y sus errores. El pago conciliado, el que sigue en verificaci├│n, el rechazado, el revertido y el arrepentido (con el cobro anulado en Khipu, o pagado justo antes de anularlo); que otro monto u otra transacci├│n no se aprueben; que un pago real no se confirme por la simulaci├│n; la consulta peri├│dica que cierra lo pagado y vence lo abandonado, y la firma de los avisos |
| **Del doble pago** | Un segundo pago mientras otro se verifica se rechaza; el abierto sin pagar se anula (Khipu lo borra, la preferencia de Mercado Pago vence en el acto) y queda vencido; una cuota pagada que ms-debt todav├¡a no abona no se cobra de nuevo; el cobro guarda las cuotas que cubre, y ms-debt las informa en orden. Si igual se paga dos veces, el segundo queda `duplicated`, sin abonar y con la referencia y el RUT para devolverlo; pagar otras cuotas de la misma deuda no es duplicado. La consulta peri├│dica no vence lo que Khipu o Mercado Pago est├ín verificando y anula en Khipu lo que vence; un vencido que se paga despu├⌐s se registra, por la revisi├│n de vencidos o por el aviso de Khipu |
| **De los intereses** | La mora de cada cargo atrasado desde el d├¡a siguiente a su vencimiento, con lo pagado imputado a lo m├ís antiguo; la cuota del convenio que crece solo sobre su capital; el redondeo en pesos y en UF; el convenio en sistema franc├⌐s, con la ├║ltima cuota que absorbe el redondeo; que sin tasa todo quede como antes; que el cobro fije el capital y el inter├⌐s y el aviso los lleve separados; la tasa que no es un n├║mero o que supera el tope |
| **De las campa├▒as** | Que cada recordatorio salga el d├¡a que dice la cadencia y no antes; los intentos, las fechas y el estado de la campa├▒a; que pare con el pago, el convenio, el reclamo o el retiro; el horario, el domingo y el feriado; los feriados calculados contra los publicados (2023, 2026 y 2027 completos, y cada regla contra un a├▒o real: el 2 de enero, el 17 y el 20 de septiembre, los que se corren al lunes, el 31 de octubre y el solsticio al minuto); el l├¡mite de dos por semana con dos d├¡as entre uno y otro, y el horario de la ley, que frenan tambi├⌐n la invitaci├│n, el recordatorio de cuota y el reenv├¡o del c├│digo; el estado que manda APOFYX; el acreedor que cobra sin agencia: registra, pausa y termina su campa├▒a sin mandato, no puede registrar la de otro, sus carteras (JSON y planilla) la nombran en el lote, y el avance le llega a ├⌐l; desde el portal, solo una empresa ve y cambia sus campa├▒as, sin un id DataBridge le pone uno, cambiar el estado no pisa el nombre ni el inicio, y se rechazan la cadencia que no crece o trae ceros, los intentos fuera de 1 a 10, el fin antes del inicio y un id de m├ís de 64 caracteres |
| **Del descuento por pronto pago** | El tramo por los d├¡as del cargo impago m├ís antiguo (30, 31, 62, 100 y 150 d├¡as); el % de la campa├▒a recortado al m├íximo vigente del mandato, que baja si el acreedor lo baja; sin m├íximo, con el mandato vencido, con la campa├▒a pausada o fuera de sus fechas, nada; el acreedor sin agencia se autoriza hasta 100. El mandato guarda su m├íximo y lo cambia al reenviarse; la campa├▒a rechaza un tramo que no existe o un valor fuera de 0 a 100 (`descuento_invalido`) y uno sobre el m├íximo (`descuento_sobre_tope`), y `{}` le quita el descuento. Al cobrar: el ejemplo del contrato ($900.000 + $18.800 ΓêÆ $9.400 = $909.400), sin descuento en un pago de parte de la deuda ni en convenio, la oferta que ve el deudor con su fecha, el descuento fijo en ms-payments desde que se abre el cobro, y el aviso que lo lleva hasta el acreedor, tambi├⌐n cuando se condona toda la mora |
| **Del asistente** | Las reglas: los montos en pesos y en UF sin sumarlos entre s├¡, el ├ínimo (la desconfianza pesa m├ís que la cortes├¡a) y el tono de cada respuesta. Lo que dice de la plata, igual que el portal: el total de hoy con la mora, la tasa que pact├│ el acreedor (tambi├⌐n en las cuotas), que sin tasa no hay inter├⌐s, y el descuento por pronto pago con su fecha. Groq y Gemini se prueban con servidores HTTP ficticios: una sola llamada a Chat Completions, source correcto, health sin secretos y categor├¡as de error sanitizadas. Si el proveedor falla o contesta en blanco, responde con las reglas solo despu├⌐s de obtener contexto autorizado; viajan los ├║ltimos doce mensajes. Los errores de sesi├│n o deuda conservan 401/403/404/502/503 y nunca se convierten en ├⌐xito vac├¡o. Arranca solo y publica su salud y su Swagger |
| **De los roles** | El `admin` de DataBridge entra con su propio rol y sin empresa, y el usuario de una empresa sigue entrando como antes; una llave de empresa no renueva como `admin`. El primer `admin` sale de `ADMIN_CORREO` una sola vez, y a una persona de una empresa no se le da ese rol. En los tres servicios, el panel responde al `admin`, `403` a una empresa y a un deudor, y `401` sin sesi├│n, tambi├⌐n en una ruta que todav├¡a no existe; y el `admin` recibe `403` en las deudas y en los pagos. En el gateway, cada tramo del panel llega a su servicio y `/internal` sigue sin puerta |
| **Del alta de empresas y personas** | Los totales de una empresa cuentan lo suyo como acreedora y lo que entreg├│ como agencia, sin mezclar pesos con UF y sin leer una deuda. Una empresa nueva pide un RUT v├ílido y que no exista. Una persona creada bajo una empresa queda como `usuario`, aunque el cuerpo pida otro rol, y un correo de una empresa no puede pasar a `admin`. Sin la empresa en ms-debt, o si ms-debt no responde, no se crea a nadie. Quitar el acceso cierra las sesiones de esa persona y anula sus enlaces sin abrir, y un `admin` no se lo puede quitar a s├¡ mismo. Cada alta y cada cambio quedan anotados con qui├⌐n los hizo |
| **Del correo** | Brevo contra un servidor falso: lo que pide su API, con la clave en su cabecera y nunca en un error. De qui├⌐n es la culpa de un correo que no sale: la direcci├│n (rechazada o mal escrita) o el servicio (una clave mala, el cupo, una IP bloqueada, un servidor que no responde, un remitente mal configurado). El modo de prueba: lo que se desv├¡a, el alias de la cuenta del equipo, y que lo que no deja salir no es un fallo. Cada correo en texto y en HTML; el del deudor trae el c├│digo y el acreedor pero ning├║n enlace, ni el monto; el nombre del acreedor no mete HTML; el adelanto del buz├│n no trae el c├│digo. El correo de prueba dice ad├│nde lleg├│ o por qu├⌐ no |
| **De la carga por campa├▒a** | Sin campa├▒a no entra nada ni se revisa; en una campa├▒a terminada tampoco. La planilla entra en su campa├▒a, con el lote y el corte armados solos o con los que manda el portal. La revisi├│n deshace siempre lo que hizo y dice cu├ínto entrar├¡a por moneda, tambi├⌐n cuando la ingesta rechaza el lote |
| **De la planilla de Excel** | Sobre la plantilla de verdad, con celdas de su tipo: una persona que debe tres meses es una deuda con tres cargos; el RUT con o sin puntos; UF con decimales y CLP entero, exactos; el cliente al d├¡a sin cargos y el retiro con su motivo; meses, fechas y montos escritos como texto (`jul-2026`, `350.000`). Cada problema con su hoja, fila y columna, y todos juntos: un RUT que no est├í en *Deudores*, un mes, una fecha o un monto mal escritos, una deuda que cambia de moneda entre meses, un mes repetido, un CLP con decimales, un monto en cero, un al d├¡a con meses, un retiro sin motivo, un deudor repetido o sin correo. La plantilla trae sus tres hojas, las listas, los tipos y la mora m├¡nima vigente; solo una empresa la descarga, y los problemas salen en `error.detalles` |
| **De la entrega por API apagada** | Sin `INTEGRACION_API`, `/api/v1` y `/api/claves` responden `404` sin mirar la clave, y Swagger muestra el contrato vac├¡o y por qu├⌐. Con `INTEGRACION_API=true`, el contrato y las claves funcionan como antes |
| **De Swagger** | Cada grupo ofrece en *Authorize* solo la llave que usan sus operaciones, sin quit├írsela a los otros grupos; lo p├║blico se llama por la direcci├│n desde la que se abri├│ Swagger, y lo interno dice que con Docker no se alcanza desde el navegador y c├│mo llamarlo. ms-ai solo publica rutas que el gateway le manda (`/api/ai/**`) |
| **De las reglas del panel** | Cada regla dice cu├ínto vale y de qu├⌐ variable sale, o que la fija la ley; c├│mo entran los deudores; el horario apagado para probar, a gritos; y los feriados, los mismos que usan las campa├▒as |
| **De la capa web** (`@WebMvcTest` + MockMvc) | Cada controlador con su seguridad, su validaci├│n y su JSON: `401` sin sesi├│n, `403` con la deuda de otro, `400` con datos malos, los `_links` seg├║n qui├⌐n mira, la cookie de la sesi├│n y los nombres del contrato v1 intactos. El aviso de Khipu llega con el cuerpo tal como vino, porque sobre ese texto va la firma. En el gateway, que el retorno de Webpay pase sin `Origin` y nada m├ís |
| **Del contrato del aviso de pago** | ms-payments manda exactamente el ejemplo del contrato ([`docs/eventos`](docs/eventos/README.md); cada uno guarda una copia en sus pruebas, y la CI revisa que sean iguales), por el exchange y la clave que dice; ms-debt escucha en esa cola y entiende cada campo; un mensaje no puede elegir qu├⌐ clase se crea al leerlo |
| **De seguridad** | **CodeQL** (Java y JavaScript) en cada pull request y cada semana; la auditor├¡a de dependencias del portal, que rompe su workflow ante una vulnerabilidad alta; Dependabot por m├│dulo, tambi├⌐n para las acciones de GitHub; y el escaneo de secretos de GitHub con protecci├│n de push, que rechaza un push que traiga un token conocido |
| **De rendimiento** (k6) | C├│mo lo siente una persona, d├│nde est├í el techo y cu├ínto aguanta abrir cobros (`pagos.js`, solo con las pasarelas simuladas). Se corren a mano, con el sistema arriba ([`docs/rendimiento`](docs/rendimiento/README.md)) |

</details>

---

## 12. Innovaci├│n

**┬┐Qu├⌐ problema resuelve?** Est├í en [┬º1](#qu├⌐-problema-resuelve): cobrar deudas chicas cuesta m├ís
que la deuda, y el deudor que quiere pagar se topa con un horario de oficina.

**┬┐Qu├⌐ hace diferente a la soluci├│n?**

1. **Se entra sin cuenta.** El deudor no crea un usuario ni inventa una contrase├▒a: escribe su RUT
   y el c├│digo que le lleg├│. Una cuenta m├ís es una raz├│n m├ís para no pagar, y una base de
   contrase├▒as m├ís que se puede filtrar.
2. **Una empresa empieza sin integrar nada.** Un `admin` la da de alta, y su gente crea una
   campa├▒a y carga a sus deudores con una planilla, viendo antes qu├⌐ entrar├¡a y qu├⌐ no. No hace
   falta un sistema propio ni tocar c├│digo. La que s├¡ tiene uno puede, con la entrega por API
   encendida, hablarle a DataBridge por un contrato versionado, sin una base compartida: as├¡ se
   prob├│ la cadena completa con APOFYX y Patrimonio.
3. **La empresa ve todo lo que pasa.** Lo dif├¡cil de la cobranza tercerizada no es cobrar: es que
   el que encarg├│ la cobranza se entere. Cada pago, convenio y reclamo queda a la vista en el
   portal de la empresa; un reclamo frena la cobranza mientras se revisa, para que nadie le siga
   cobrando a quien dice que ya pag├│. Con la API encendida, adem├ís viaja firmado hasta el sistema
   del acreedor y deja la deuda en $0 all├í.

**┬┐Qu├⌐ valor agrega?** Al acreedor, cobranza de montos bajos que antes no era rentable, y certeza
de que su cartera est├í al d├¡a. Al deudor, pagar a cualquier hora, ver el detalle de lo que debe,
repactar en cuotas y reclamar sin llamar a nadie. A la empresa que cobra, una cartera
que se carga con una planilla, revisada antes de confirmar, y una campa├▒a que se cumple sola dentro
de lo que permite la ley, sin que nadie lleve la cuenta.

---

## 13. Recorrido de demostraci├│n

1. **La empresa entra.** Con la demo encendida (`DEMO_DATOS=true`) en una base nueva, en *Soy de
   una empresa*, `camila.reyes@example.com` pide su c├│digo, que llega al buz├│n, y ve las dos
   campa├▒as de Servicios Horizonte.
2. **Carga a sus deudores en una campa├▒a.** En *Cargar cartera* elige una campa├▒a o la crea, descarga
   la plantilla de Excel, la llena (una hoja de deudores y otra de deudas, una fila por mes) y la sube.
   Ve qu├⌐ entrar├¡a y qu├⌐ no, con su motivo; si la planilla tiene problemas, cada uno con su hoja, fila
   y columna. Reci├⌐n al confirmar entran. C├│mo probarla est├í en
   [`docs/cartera`](docs/cartera/README.md).
3. **El deudor recibe su c├│digo solo.** Al entrar la deuda, la invitaci├│n sale a su correo, y la
   cartera muestra *Invitado el ΓÇª*. La pantalla no muestra el c├│digo: quien lo viera podr├¡a entrar
   en su lugar.
4. **El deudor entra** con su RUT y ese c├│digo. Puede simular un plan, aceptarlo y pagar una o
   varias cuotas, en orden.
5. **O reclama.** *No reconozco esta deuda*, con un motivo y, si quiere, un detalle. La deuda queda
   **En revisi├│n**. La empresa la ve con el filtro del mismo nombre, y toca **Reanudar cobro** o
   **Retirar**.
6. **Paga con Webpay**, con la [tarjeta de prueba](#para-pagar-con-webpay), en la p├ígina real de
   Transbank; o con Khipu, con el banco de prueba ([c├│mo activarlo](#para-pagar-con-khipu-de-verdad)).
7. **El pago llega a la empresa.** ms-payments avisa a ms-debt, que abona la deuda. Con la
   entrega por API encendida y suscripciones configuradas, los sistemas conectados pueden recibir
   sus eventos. La demo independiente no necesita esa integraci├│n.
8. **Queda el rastro.** El deudor ve el pago en su historial, con qu├⌐ cuotas cubri├│, y descarga el
   comprobante. La empresa lo ve en *Pagos recibidos*.

**Las marcas de las pasarelas.** Webpay, Mercado Pago y Khipu aparecen con su logo oficial, tal
como Transbank, Mercado Pago y Khipu los publican para los comercios, porque es lo que el deudor
reconoce. Los logos son marcas de sus due├▒os. Las simuladas lo dicen en pantalla: *Simulaci├│n*.

**Pagos en UF.** Usan la UF de ese d├¡a exacto, del **Banco Central**. Como la publica con un mes
de adelanto, ms-payments la carga al arrancar y cada ma├▒ana a las 9:30. Sin credenciales, se carga
a mano:

```powershell
$cuerpo = @{ dia = "2026-10-03"; valor = "39876.54" } | ConvertTo-Json
$cuerpo | docker compose --profile app exec -T ms-payments sh -c 'curl -s -X POST http://127.0.0.1:8084/internal/uf -H X-Internal-Key:$INTERNAL_KEY -H Content-Type:application/json --data-binary @-'
```

---

## 14. Estado al 19 de octubre de 2026

**1320 pruebas Java sin fallos**, y el portal compila (768 m├│dulos). El acceso por c├│digo de los tres portales se comprob├│ por el gateway y en Edge, usando solo el proyecto Docker `tb_web`, cuentas ficticias y Mailpit sin salida externa. Las verificaciones de otros recorridos de la tabla son anteriores; no se repitieron para este cambio de acceso.

En la revisi├│n de #222 se corrigi├│ el consumo prematuro de un acceso correcto cuando ms-debt no
responde al comprobar la empresa: c├│digo y enlace se consumen solo despu├⌐s de abrir la sesi├│n. Las
dos regresiones simulan la ca├¡da y recuperan el mismo acceso sin crear sesiones durante el rechazo.
No se repiti├│ una ca├¡da de ms-debt en Docker ni se enviaron correos reales para esa correcci├│n.

La demo por campa├▒a ([#192](https://github.com/TechnicalBridge/TB_web/issues/192)) se comprob├│ en un
proyecto de Docker aparte, con su propia base vac├¡a y el correo solo al buz├│n de prueba: se repitieron
las suites completas de ms-auth y ms-debt, y el recorrido de la empresa en Edge. Las dem├ís
verificaciones de la tabla son anteriores y no se repitieron para estos cambios.

La confirmaci├│n de Mercado Pago (GHSA-c9xr) se verific├│ con clientes simulados y pruebas HTTP del
controlador: solo se abona el pago de la preferencia guardada, con su identificador, referencia, monto en
pesos y moneda. No se repiti├│ un cobro contra la pasarela real para este cambio.

<details>
<summary><b>Cada verificaci├│n</b>, con su resultado</summary>

| Verificaci├│n | Resultado |
| --- | --- |
| Pruebas Java (cada servicio con su `mvnw -f <servicio>\pom.xml clean test`, JDK 25) | **1320**, sin fallos ([┬º11](#11-pruebas)); las cinco se repitieron con #312; gateway, ms-ai y ms-debt, con #234 (las cinco, con #228), ms-auth con #152, ms-debt y ms-payments sobre `main` con #139 y #258 juntos, ms-auth con #141; ms-auth, ms-debt y ms-payments con #238, ya con las sesiones ES256 de #256; ms-debt con #46, ms-auth con #236, ms-payments con la confirmaci├│n de Mercado Pago, ms-debt con #154 y ms-auth con #192, el resto conserva la ejecuci├│n anterior. En Windows se us├│ `-DargLine=-Djdk.net.hosts.file=C:\Windows\System32\drivers\etc\hosts` para evitar una demora del DNS en los correos simulados |
| La marca ([#274](https://github.com/TechnicalBridge/DataBridge/issues/274)) | Los correos de c├│digo, recordatorio, prueba y acceso con enlace llegaron a Mailpit con el logo del portal, una sola imagen, y en Edge se ven bien en claro y en oscuro (verde, sin el morado de antes), a 1360 y 390 px. El portal carga sus cuatro ├¡conos, sin errores, en los dos temas. El s├¡mbolo contrasta 4,7:1 con la crema y 8,4:1 con el carb├│n. Las direcciones eran `@example.com`, y lo que crearon las pruebas se borr├│ |
| Tres bases ([#238](https://github.com/TechnicalBridge/TB_web/issues/238)) | En un proyecto de Docker aparte, desde cero: cada MySQL tiene solo su base y sus migraciones, la clave de la aplicaci├│n de un servicio no entra a la base de otro, y con `db-debt` apagada siguen ms-auth, ms-payments y el portal (ms-debt se recupera solo al volver). Cada instancia usa unos 200 MB. En este equipo, con respaldo previo, el script de entonces pas├│ los datos (1.238, 1.677 y 346 filas, iguales tabla por tabla) y todo qued├│ sano: por el gateway y en Edge se ve lo de siempre. Con Maven, ms-debt arranca contra su base con el mismo `.env` |
| C├│digo primero ([#222](https://github.com/TechnicalBridge/TB_web/issues/222)) | Migraci├│n V6 sobre MySQL con datos, con respaldo previo. Deudor, empresa y admin solicitan solo c├│digo y entran por el gateway desde Edge. El respaldo entrega c├│digo y enlace en un ├║nico mensaje de Mailpit. Canjeados simult├íneamente, solo uno abre sesi├│n; un intento incorrecto persiste despu├⌐s del `401`. Las tres entradas se revisaron a 1360 y 390 px, en ambos temas. Sin correos externos ni carga de la planilla del equipo. El 19 de octubre, la empresa pidi├│ su c├│digo por el gateway y el correo sali├│ por Brevo, desviado por el modo de prueba |
| Demo por campa├▒a ([#192](https://github.com/TechnicalBridge/TB_web/issues/192)) | En un proyecto de Docker aparte, con MySQL vac├¡o y `DEMO_DATOS=true`: ms-debt instala una empresa acreedora, dos campa├▒as y seis deudas, sin agencias ni mandatos. En Edge, `camila.reyes@example.com` pide su c├│digo, lo recibe en el buz├│n de prueba y entra; ve `DEMO-PESOS` y `DEMO-UF`, **crea una campa├▒a nueva** (`200`) y ve a los seis deudores en su cartera. *Campa├▒as* a 1360 y 390 px, en ambos temas, sin salirse del ancho y sin errores de consola. Apagada no crea nada; con datos propios no completa nada; reiniciar no duplica (pruebas de `DataSeeder`) |
| Devoluci├│n de un pago duplicado ([#153](https://github.com/TechnicalBridge/TB_web/issues/153)) | Con todo Docker reconstruido desde la rama, en Edge, sobre el pago duplicado real de la base (y dej├índolo despu├⌐s como estaba): la empresa toca *Ya lo devolv├¡*, anota una referencia con comillas y el pago pasa a *Un pago devuelto* con su fecha; el libro guarda un JSON v├ílido con la referencia intacta, la empresa y la persona. Marcarlo otra vez por la API responde `200` sin cambiar nada. El deudor lo ve en su historial. A 390 px, en ambos temas, sin desbordes ni errores de consola |
| Modelo de negocio ([#200](https://github.com/TechnicalBridge/TB_web/issues/200)) | Por el gateway, empresa y admin coinciden en cinco meses de prueba, con los cortes de $800.000, $1.000.001 y $12.000.000, un pago en UF con sus pesos guardados y un mes vac├¡o. Una segunda empresa no ve los pagos de la primera; deudor, empresa y admin respetan sus permisos. Reasignar una deuda conserva la atribuci├│n de los pagos nuevos. En Edge, a 1360 y 390 px y en ambos temas, Campa├▒as y Empresas no se salen del ancho. Cambiar de mes limpia las cifras anteriores, incluso con una respuesta fallida. Los ocho diagramas afectados se dibujaron con Mermaid 11 |
| Asistente en Java | Antes de borrar la versi├│n en Python se compararon las dos: 1148 comparaciones de las reglas, sin diferencias. En vivo, por el gateway y con la sesi├│n de tres deudores, 30 de 30 respuestas iguales. En Edge, el deudor conversa con el asistente en tema claro y oscuro, en escritorio y en celular |
| Webpay | Contra el ambiente de integraci├│n de Transbank, con los contenedores reconstruidos y en Edge: el deudor anula en Webpay y el portal dice *El pago no se complet├│*; paga con la tarjeta de prueba y vuelve con *Pago aprobado*, el pago queda `paid` con la respuesta `AUTHORIZED` guardada, y el contrato de Patrimonio queda con lo que corresponde |
| Build del portal | Correcto, 769 m├│dulos |
| Cadena completa, sobre los contenedores reconstruidos | **16 de 16** comprobaciones: el cliente moroso nuevo llega desde Patrimonio, DataBridge registra al acreedor y lo invita, el deudor reclama y la disputa llega al acreedor, la agencia reanuda, el pago vuelve hasta el contrato y un lote repetido no se procesa dos veces |
| Khipu | Contra un Khipu falso con la forma de la API v3, con los contenedores reconstruidos y en Edge: el deudor se arrepiente y el portal dice *El pago no se complet├│*; paga y dice *Pago aprobado*; paga y cierra la ventana sin volver, y la consulta peri├│dica lo registra sola. En los tres casos el contrato de Patrimonio queda con lo que corresponde. Y contra **Khipu real**, con una cuenta en modo desarrollador: se pag├│ con DemoBank, Khipu lo concili├│ (4 min 20 s desde que se abri├│ el cobro, contando lo que tarda la persona en Khipu) y ms-payments lo registr├│ 21 s despu├⌐s, cuando todav├¡a revisaba cada 30 s Al cancelar, el cobro se anula en Khipu: probado contra Khipu real, donde queda `deleted` y ya no se puede pagar |
| Mercado Pago | Contra el Mercado Pago real con credenciales de prueba, con ms-payments reconstruido y en Edge: la preferencia se crea con vencimiento a 30 minutos; el deudor paga con la tarjeta de prueba y no vuelve al portal, y la consulta peri├│dica registra el pago en unos 10 s: queda `paid` y la deuda del contrato en Patrimonio baja a $0 |
| Doble pago | Contra las pasarelas reales, con la migraci├│n `V2` aplicada sobre la base con datos: mientras Khipu verifica, otro pago por la misma cuota se rechaza (*Tienes un pago en verificaci├│n*); reci├⌐n pagada la cuota, antes de que ms-debt la abone, tambi├⌐n; un cobro de Khipu abierto sin pagar queda `deleted` en Khipu y vencido aqu├¡ al abrir otro, y la preferencia de Mercado Pago vence en el acto. Pagados a la vez un cobro de Webpay y uno de Khipu por la misma cuota, el primero se abona y el segundo queda `duplicated`: la deuda baja una sola vez, el deudor ve *Estas cuotas ya estaban pagadas* y la empresa lo ve en *Pagos para devolver* con la referencia de Khipu |
| Webpay seg├║n Transbank ([#75](https://github.com/TechnicalBridge/TB_web/issues/75)) | En Edge contra el ambiente de integraci├│n, con ms-payments y el portal reconstruidos: 11 de 11 comprobaciones. La p├ígina abierta dos veces dice *Webpay ya se abri├│* en vez del Error 21, y la primera ventana paga igual; una vuelta por tiempo con una sesi├│n inventada responde 400 y no toca el cobro; *Abrirla de nuevo* abre un cobro nuevo y vence el anterior; **pagado y sin volver al portal, ms-payments lo confirm├│ solo en 7 segundos**, y la Mastercard de prueba queda rechazada. El cobro que hab├¡a quedado abierto por el Error 21 se venci├│ solo, despu├⌐s de consultarlo |
| Carga de pagos (`rendimiento/pagos.js`) | Con las pasarelas simuladas, hasta 100 deudores abriendo cobros a la vez: 5.817 peticiones sin una falla, abrir un cobro en 30 ms y consultarlo en 7 ms (p95) |
| Reclamo en pantalla | Recorrido en Edge: el deudor reclama, la empresa lo ve con su detalle y lo retira, y el acreedor ve *Disputa aceptada* |
| Inter├⌐s por mora | Con los tres sistemas reconstruidos y Khipu real: un contrato de Patrimonio al 2% mensual, con tres arriendos de $300.000 atrasados 62, 31 y 1 d├¡a, lleg├│ por APOFYX con su tasa. El portal mostr├│ $18.800 de mora, se pagaron $918.800 con Khipu, y Patrimonio dej├│ los cargos en $0 y anot├│ $18.800 de intereses |
| Convenio con inter├⌐s | Otro contrato, al 1,5%, repact├│ $914.100 (capital m├ís mora) en 6 cuotas de $160.448, la ├║ltima de $160.445: $962.685 en total, $48.585 de intereses del convenio |
| Campa├▒a | Creada en APOFYX con la cadencia 1, 4, 11 y 3 intentos, y avanzada d├¡a por d├¡a con la pasada de prueba. Salieron la invitaci├│n el martes 6 de octubre, el primer recordatorio el 8 y el segundo el 13. No sali├│ nada el 7 ni el 10 (el l├¡mite de la ley), el domingo 11, el feriado del 12 ni el 13 a las 7:30. Pausada en APOFYX, el 20 no sali├│ nada. Tres correos en total, en Mailpit |
| Pantallas de intereses y campa├▒as | En Edge: el formulario de campa├▒a de APOFYX pide la cadencia y ofrece solo correo, y pausar o reanudar llega a DataBridge; el deudor ve su mora y a qu├⌐ tasa, el convenio con su inter├⌐s y el pago desglosado; Patrimonio muestra la tasa del contrato, el inter├⌐s cobrado y el campo al firmar |
| Campa├▒as en el portal ([#78](https://github.com/TechnicalBridge/TB_web/issues/78)) | En Edge como la empresa, con ms-debt y el portal reconstruidos: 31 de 31 comprobaciones. Una campa├▒a para Patrimonio, creada sin id, recibe uno de DataBridge; la cadencia que no crece o trae ceros y los intentos fuera de 1 a 10 no dejan crear, y la de 1, 2, 5 muestra la advertencia de la ley. La campa├▒a aparece en *Cargar* y una deuda de prueba entra con ella: pausada, la pasada no le env├¡a nada, y reanudada le escribe. Cambiarla no pisa el inicio ni el estado, y terminada ya no ofrece nada ni contacta. La campa├▒a de otra empresa responde 404. En los dos temas, a 1440, 390 y 360 px, sin scroll horizontal |
| `main` protegida | En los tres repositorios, un push directo a `main` se rechaza (*GH013: Changes must be made through a pull request*): solo entra un PR, con squash y con la CI en verde. En TB_web, un PR que solo cambia el README pasa los seis checks `CI de <m├│dulo>` sin correr las pruebas |
| Descuento por pronto pago ([#45](https://github.com/TechnicalBridge/TB_web/issues/45)) | En Edge, con ms-debt, ms-payments y el portal reconstruidos y las migraciones `V5` aplicadas en las dos bases: el acreedor autoriza hasta 100% en el mandato, la empresa crea en el portal una campa├▒a con 0/50/100 y carga tres arriendos de $300.000 al 2% atrasados 62, 31 y 1 d├¡a. El deudor ve *te descontamos $9.400ΓÇª Pagar├¡as $909.400 en vez de $918.800*, paga $909.400 con Webpay (tarjeta de prueba) y queda registrado en todos lados: el pago con $9.400 de inter├⌐s y $9.400 de descuento, la deuda saldada, `pago.confirmado` con su desglose, el historial, *Pagos recibidos* y el comprobante en PDF. En los dos temas, a 1440, 390 y 360 px |
| Migraciones sobre una base con datos | `V3` aplicada y el secreto existente cifrado al arrancar |
| Roles y panel ([#137](https://github.com/TechnicalBridge/TB_web/issues/137), [#138](https://github.com/TechnicalBridge/TB_web/issues/138)) | `V4` de `tb_auth` probada en una base aparte y aplicada sobre la real. En vivo, 34 de 34 comprobaciones de los roles; en Edge, 18 de 18. El `admin` da de alta empresas y personas desde el panel, y la invitaci├│n llega con su enlace de un solo uso |
| Correos de verdad ([#62](https://github.com/TechnicalBridge/TB_web/issues/62)) | Los cinco correos (c├│digo, recordatorio, enlace, invitaci├│n y prueba) salieron por la API de Brevo y llegaron a un Gmail del equipo, en HTML y en texto. Un correo que no sale no cuenta como contacto, y la empresa ve el motivo en su cartera |
| Horario de la ley en todos los correos ([#187](https://github.com/TechnicalBridge/TB_web/issues/187)) | Con los tres sistemas en Docker, un mi├⌐rcoles a las 22:30: cuatro deudas nuevas llegaron por la cadena y quedaron en cobranza sin ning├║n contacto, con el aviso *se invita en la pr├│xima pasada*. Las invitaciones salieron con la pasada de las 8:00 |
| Operaci├│n en el panel ([#139](https://github.com/TechnicalBridge/DataBridge/issues/139)) | Con Docker reconstruido desde la rama y las migraciones `V11` de `tb_debt` y `V6` de `tb_payments` aplicadas, con respaldo previo. Por el gateway, 24 de 24: los 21 avisos fallidos a APOFYX salen por destinatario, con sus intentos y su ├║ltimo error y sin su contenido; con un receptor de prueba en su puerto, uno reintentado se entrega y queda anotado sobre la empresa, y reintentarlo otra vez responde `409`; sin receptor, los 20 restantes vuelven a fallidos tras su intento extra. Los totales de pagos calzan con la base; una UF cargada a mano queda como la de ese d├¡a, con qui├⌐n la carg├│, y una negativa responde `400`. Un aviso de pago reintentado llega a ms-debt sin abonar dos veces. Una empresa recibe `403`. En Edge, a 1360 y 390 px y en los dos temas: reintentar desde el bot├│n, la pantalla que se actualiza sola, la UF escrita como en Chile, y con ms-payments detenido sus bloques dicen *No responde* mientras los avisos a las empresas se siguen viendo. Al terminar, los avisos quedaron como estaban y sin la UF de prueba |
| Cambiar `CIFRADO_LLAVE` ([#258](https://github.com/TechnicalBridge/DataBridge/issues/258)) | En este equipo, con respaldo previo del `.env` y de las tres bases y Docker reconstruido entero desde la rama, 18 de 18: `preparar-env.ps1 -Cambiar CIFRADO_LLAVE` dej├│ la de antes en `CIFRADO_LLAVE_ANTERIOR` sin mostrar ninguna; al arrancar, ms-debt pas├│ el secreto de la suscripci├│n de APOFYX a la llave nueva, que ya no se abre con la vieja y es el mismo secreto, y un aviso entregado despu├⌐s lleva la firma hecha con ├⌐l. Con un secreto que no abre con ninguna llave, ms-debt no arranc├│, dijo qu├⌐ suscripci├│n era y la base qued├│ id├⌐ntica; sacado ese, pas├│ las dem├ís. Ning├║n registro muestra una llave ni un secreto |
| Accesos en el panel ([#141](https://github.com/TechnicalBridge/DataBridge/issues/141)) | Por el gateway: tres c├│digos equivocados contra el mismo RUT salen juntos al filtrar por resultado, como `21.***.***-K`; un enlace pedido para un correo sin cuenta sale como rechazado; recorriendo las 9 p├íginas est├ín las 411 filas de la bit├ícora, y ninguna trae un RUT completo, un correo, una IP ni su huella; los totales de hoy calzan con la base, y una empresa recibe `403`. En Edge, a 1360 y 390 px y en los dos temas: los filtros, las p├íginas y la tabla entera sin desplazarse de lado |
| Suspender una empresa ([#152](https://github.com/TechnicalBridge/DataBridge/issues/152)) | Con respaldo previo de las tres bases y Docker reconstruido entero desde la rama (`V12` aplicada), por el gateway 30 de 30, suspendiendo APOFYX: con su JWT vigente su persona recibe `403`, al renovar recibe `401` y toda la familia de la sesi├│n queda revocada, pedir un c├│digo responde `202` como siempre pero no crea ninguno, y un enlace suyo ya no abre sesi├│n; el deudor de una deuda que cobra APOFYX la ve `enPausa`, y pagarla o repactarla responde `409` sin crear ning├║n pago. Suspender o reactivar dos veces no anota dos veces. Reactivada, su persona entra, ve su cartera y renueva, y la deuda se puede pagar de nuevo. En Edge, a 1360 y 390 px y en los dos temas, 37 de 37: la confirmaci├│n dice lo que se congela, la ficha y la lista dicen *Suspendida* y el historial anota qui├⌐n; al recargar, su persona queda fuera; el deudor ve *Por ahora no se puede pagar*, sin *Pagar* ni *Pagar en cuotas* pero con *No reconozco esta deuda*, y entrar directo a pagar o repactar muestra la pausa; al reactivarla vuelve *Pagar*. Sin errores de consola. Despu├⌐s de la revisi├│n, 8 de 8 por el gateway: con Patrimonio suspendida, APOFYX (activa) ve su deuda en pausa y reenviarle el c├│digo al deudor responde `409` sin dejar ning├║n contacto; y en Edge, 10 de 10: la ficha suspendida lo dice con una nota y sin el formulario para dar acceso. APOFYX y Patrimonio quedaron activas |
| Acreedor suspendido ([#296](https://github.com/TechnicalBridge/DataBridge/issues/296)) | Con respaldo previo de las tres bases y Docker reconstruido entero desde la rama, por el gateway, 17 de 17. APOFYX declara un mandato con un acreedor de prueba y le crea una campa├▒a. Con el acreedor suspendido por el admin: las opciones de carga lo marcan, la revisi├│n y la carga responden `409` (`acreedor_suspendido`) y no entra ninguna deuda; el mandato lo dice, no se renueva ni se declara otro, y s├¡ se termina. Reactivado, la revisi├│n vuelve a pasar y el mandato se renueva. En Edge, 37 de 37 (*Cargar cartera* y *Mandatos*, a 1360 y 390 px, en los dos temas, sin errores). Lo que cre├│ la prueba se borr├│ |
| Mandatos sin la API ([#234](https://github.com/TechnicalBridge/DataBridge/issues/234)) | Con respaldo previo de las tres bases y Docker reconstruido entero desde la rama (`V13` aplicada), por el gateway 32 de 32: APOFYX ve su mandato con Patrimonio y las deudas que sigue cobrando; no puede declarar otro con Patrimonio (`409`) ni uno con un acreedor nuevo sin su raz├│n social o con un t├⌐rmino que ya pas├│ (`400`); antes del mandato no le puede cargar cartera ni crearle campa├▒as, y despu├⌐s s├¡, con el acreedor registrado y el mandato a nombre de quien lo declar├│; lo renueva y lo termina, y terminado ya no carga ni crea campa├▒as, pero pausa la que ten├¡a. Una empresa que cobra directo recibe `agencia: false` y no puede declarar, y un deudor recibe `403`. En Edge, a 1360 y 390 px y en los dos temas, 23 de 23: la secci├│n *Mandatos* de *Campa├▒as*, declarar (con la casilla de la declaraci├│n), renovar y terminar con su confirmaci├│n; la campa├▒a nueva ofrece y deja de ofrecer al acreedor; Instituto Andes no ve nada de mandatos, y la ficha del panel muestra cada estado. Sin errores de consola. Lo que crearon las pruebas se borr├│ despu├⌐s |
| Cerrar la ventana de la pasarela ([#307](https://github.com/TechnicalBridge/DataBridge/issues/307)) | Con respaldo previo de las tres bases y Docker reconstruido entero desde la rama, por el gateway, 14 de 14: sin sesi├│n `401`; otro deudor, la empresa y el admin `403` y el cobro sigue abierto; el deudor lo cancela y queda `expired` con el evento `portal` en el libro; cancelar dos veces no agrega nada; un pago que no existe `404`; y despu├⌐s se abre otro cobro. En Edge, 32 de 32, abriendo un cobro de verdad en Webpay (integraci├│n), Khipu y Mercado Pago, cerrando la ventana sin pagar y cancel├índolo: el portal lo detecta, pregunta y queda libre para elegir de nuevo. Ning├║n cobro se pag├│, y los de la prueba se borraron. Lo que no se prob├│ en vivo, porque habr├¡a que pagar (ya pagado, en verificaci├│n) o tirar la pasarela (no responde), lo cubren las pruebas de ms-payments |
| Reglas en el panel ([#140](https://github.com/TechnicalBridge/TB_web/issues/140)) | En Edge, a 1360 y 390 px y en los dos temas: las tres tarjetas con sus reglas, los feriados desplegables, ninguna clave a la vista y, con ms-payments detenido, su tarjeta dice *No responde* |
| Carga en una campa├▒a ([#190](https://github.com/TechnicalBridge/TB_web/issues/190)) | En vivo, con una planilla de 6 deudas: sin campa├▒a, `400`; la revisi├│n dijo que entrar├¡an 4 por $2.800.000 y que 2 no (`bajo_umbral_mora` y `rut_invalido`), y la base qued├│ igual antes y despu├⌐s de revisar. Al confirmar entr├│ exactamente eso, confirmar otra vez no carg├│ nada, y las 4 invitaciones llegaron. En Edge, 44 de 44 comprobaciones |
| La entrega por API apagada ([#191](https://github.com/TechnicalBridge/TB_web/issues/191)) | Apagada, 10 de 10: `/api/v1` y `/api/claves` responden `404`, Swagger muestra el contrato vac├¡o y la carga por campa├▒a sigue. Encendida, 9 de 9: la empresa emite una clave, entra con ella, la revoca y ya no entra. En Edge, 40 de 40: el portal no ofrece claves ni habla de la API |
| La planilla en Excel ([#201](https://github.com/TechnicalBridge/TB_web/issues/201)) | En vivo, por el portal: la plantilla se descarga con sus tres hojas, las listas, las fechas y los n├║meros; una planilla correcta de ejemplo revisa con 3 deudas que entrar├¡an por $1.420.000 y 25 UF y una al d├¡a, y la de errores responde sus 11 problemas con hoja, fila y columna. La de la demo, con las cuatro cuentas del equipo, entrar├¡a sin rechazos. En Edge, 12 de 12: descargar, subir, revisar y ver los problemas, en escritorio y tel├⌐fono |

</details>

**Lo que no est├í:**

- **Mercado Pago, la vuelta al portal:** Mercado Pago descarta las direcciones de vuelta que no
  son https, as├¡ que en local el deudor no vuelve solo al portal. El pago se registra igual, por la
  consulta peri├│dica. La vuelta autom├ítica con un t├║nel https no se prob├│.
- **WhatsApp y SMS:** el contrato admite los canales, pero los c├│digos y las campa├▒as salen solo
  por correo, y APOFYX ya no los ofrece al crear una campa├▒a.
- **La tasa m├íxima convencional se pone a mano** (`INTERES_TASA_MAXIMA_MENSUAL`): la CMF la publica
  cada mes y no se trae sola.
- **Los feriados que se decretan se suman a mano** (`CONTACTO_FERIADOS`): una elecci├│n, un
  plebiscito o un feriado especial no se pueden calcular. Los nacionales se calculan solos para
  cualquier a├▒o. Los regionales (el 7 de junio en Arica y Parinacota, el 20 de agosto en Chill├ín y
  Chill├ín Viejo) no est├ín, porque DataBridge no sabe en qu├⌐ regi├│n vive el deudor.
- **Que el deudor pida que no lo contacten m├ís**, y los gastos de cobranza que permite el art. 37,
  no est├ín.
- **Varias r├⌐plicas:** ver *Escalabilidad* en [┬º9](#9-requisitos-no-funcionales).
- **Retenci├│n de datos** (decisi├│n I12 del contrato): cu├ínto se guarda una deuda saldada antes de
  anonimizarla.
- **El frontend no tiene una suite autom├ítica:** se prueba compilando y recorriendo en el
  navegador.
- **Los avisos al sistema del acreedor, con la API apagada:** nadie se puede suscribir, porque la
  suscripci├│n se hace por `/api/v1/suscripciones`. La empresa ve todo en su portal.
- **El correo en Outlook:** las plantillas se probaron en Gmail; en un buz├│n de Outlook no se
  vieron. Y un rebote que Brevo recibe despu├⌐s de aceptar un correo no llega a DataBridge, as├¡ que
  no cuenta para *correo con problemas*.

---

Este repositorio es una de tres piezas:
[**Patrimonio Inmuebles**](https://github.com/TechnicalBridge/patrimonioinmuebles) ΓåÆ
[**APOFYX**](https://github.com/TechnicalBridge/APOFYX) ΓåÆ **DataBridge**.
# Firewalls l├│gicos por dominio

Las pol├¡ticas de cuentas, integraciones de deuda y pagos, sus l├¡mites, variables, pruebas y rollback se documentan en [docs/firewalls-dominios.md](docs/firewalls-dominios.md).
