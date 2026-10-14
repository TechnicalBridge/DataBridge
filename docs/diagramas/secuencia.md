# Diagramas de secuencia

Vista de procesos del [modelo 4+1](README.md): **qué pasa en el tiempo**. Quién le habla a quién, en qué orden,
y qué corre solo aunque nadie esté mirando.

**Cómo leerlos.** Cada columna es un participante: una persona, una pantalla, una clase o un sistema. El tiempo
corre hacia abajo. Una flecha continua es una llamada y una punteada, su respuesta. Un recuadro `alt` separa
caminos que no ocurren juntos, `opt` es algo que pasa solo a veces, `loop` se repite y `par` ocurre a la vez.

Salen del código: los nombres de las columnas son las clases de cada servicio y las llamadas, sus métodos. Dónde
está cada clase lo dicen los [diagramas de clases](clases.md).

| # | Secuencia | Quién la dispara | Servicios que participan |
| --- | --- | --- | --- |
| 1 | [El deudor entra con su RUT y su código](#1-el-deudor-entra-con-su-rut-y-su-código) | El deudor | gateway, ms-auth |
| 2 | [Entrar con un enlace al correo](#2-entrar-con-un-enlace-al-correo) | Un usuario, un admin o un deudor | gateway, ms-auth, ms-debt |
| 3 | [Renovar y cerrar la sesión](#3-renovar-y-cerrar-la-sesión) | El portal | ms-auth |
| 4 | [Entregar una cartera por la API](#4-entregar-una-cartera-por-la-api) | El sistema de una empresa | ms-debt, ms-auth |
| 5 | [Simular y aceptar un convenio](#5-simular-y-aceptar-un-convenio) | El deudor | ms-debt |
| 6 | [Pagar con Khipu](#6-pagar-con-khipu) | El deudor | ms-payments, ms-debt |
| 7 | [Pagar con Webpay](#7-pagar-con-webpay) | El deudor | gateway, ms-payments, ms-debt |
| 8 | [Pagar con Mercado Pago](#8-pagar-con-mercado-pago) | El deudor | ms-payments, ms-debt |
| 9 | [El aviso del pago](#9-el-aviso-del-pago-de-ms-payments-a-quien-entregó-la-cartera) | El reloj | ms-payments, RabbitMQ, ms-debt |
| 10 | [La campaña le escribe al deudor](#10-la-campaña-le-escribe-al-deudor) | El reloj | ms-debt, ms-auth |
| 11 | [Reclamar una deuda y resolver el reclamo](#11-reclamar-una-deuda-y-resolver-el-reclamo) | El deudor y un usuario | ms-debt |
| 12 | [Preguntarle al asistente](#12-preguntarle-al-asistente) | El deudor | gateway, ms-ai, ms-debt |
| 13 | [El admin abre su panel](#13-el-admin-abre-su-panel) | El admin | gateway, ms-auth, ms-debt, ms-payments |
| 14 | [Una empresa conecta su sistema](#14-una-empresa-conecta-su-sistema) | Un usuario y su sistema | ms-debt |
| 15 | [La UF del día](#15-la-uf-del-día) | El reloj | ms-payments |
| 16 | [El admin da de alta una empresa y a su gente](#16-el-admin-da-de-alta-una-empresa-y-a-su-gente) | El admin | ms-debt, ms-auth |

En todos, el navegador solo habla con el **portal** (nginx), que manda lo que empieza con `/api` al **gateway**,
y el gateway lo enruta al servicio. Para no repetirlo en cada diagrama, esa parte se dibuja completa solo en
el primero.

---

## 1. El deudor entra con su RUT y su código

Sin cuenta y sin contraseña. El código le llegó antes por correo ([secuencia 4](#4-entregar-una-cartera-por-la-api)).

```mermaid
sequenceDiagram
    actor D as Deudor
    participant P as Portal (React)
    participant G as Gateway
    participant AC as AuthController
    participant AS as AuthService
    participant SS as SessionService
    participant B as tb_auth

    D->>P: escribe su RUT y su código
    P->>G: POST /api/auth/acceso {rut, codigo}
    Note over G: RateLimitFilter: 10 por minuto<br/>por origen en /api/auth
    alt se pasó del límite
        G-->>P: 429 Demasiadas solicitudes
    else dentro del límite
        G->>AC: la enruta a ms-auth
        AC->>AS: entrarConCodigo(rut, codigo, ip)
        AS->>B: access_log: intentos fallidos de ese origen<br/>en los últimos 10 minutos
        Note over AS: con 10 o más, responde 429
        AS->>B: el último código sin usar de ese RUT
        alt no hay código, venció, se agotó o no calza
            AS->>B: suma el intento y anota en access_log
            AS-->>P: 401 o 429, con el mismo mensaje
            Note over AS: «no hay código» y «no corresponde» responden igual:<br/>así no se puede averiguar qué RUT tienen deuda
        else el código calza
            Note over AS: compara la huella SHA-256(rut:codigo),<br/>en tiempo constante
            AS->>B: consume el código y anota granted
            AS->>SS: abrir(DEBTOR, rut)
            SS->>B: sessions: familia nueva, con la huella de la llave
            SS-->>AS: la llave de renovación, en claro una sola vez
            AS-->>AC: Sesion(JWT de 15 minutos, usuario, llave)
            AC-->>P: 200 {token, user, expiraEnSegundos}<br/>y la cookie tb_renovacion
        end
    end
    P->>G: GET /api/me, con el JWT
    G-->>P: quién es
    P-->>D: sus deudas
```

El JWT viaja en el cuerpo y el portal lo guarda **en memoria**. La llave de renovación va en una cookie
`HttpOnly`, `SameSite=Strict` y limitada a `/api/auth`: un script de la página puede usar la sesión mientras esté
abierta, pero no puede llevársela.

---

## 2. Entrar con un enlace al correo

Así entran el **usuario de una empresa** y el **admin de DataBridge**, cada uno por su puerta
(`/databridge/login` y `/admin/login`). Para el **deudor** es el respaldo, cuando no le llegó el código.

```mermaid
sequenceDiagram
    actor U as Usuario, admin o deudor
    participant P as Portal
    participant AC as AuthController
    participant AS as AuthService
    participant DC as DeudasClient
    participant MD as ms-debt
    participant M as MailService
    participant B as tb_auth

    U->>P: escribe su correo, o su RUT si es deudor
    P->>AC: POST /api/auth/enlace {correo, rut}
    AC->>AS: pedirEnlace(rut, correo, ip)
    alt viene con RUT: es un deudor
        AS->>DC: correoDelDeudor(rut)
        DC->>MD: GET /internal/deudores/{rut}/correo, con la clave interna
        MD-->>DC: el correo que registró el acreedor, o 404
        Note over AS: el enlace va solo ahí: el correo<br/>que se escribió en el formulario no se usa
    else sin RUT: es personal
        AS->>B: staff_users por correo, y que esté habilitado
    end
    alt hay a quién mandarlo
        AS->>B: magic_links: la huella del enlace, vigente 15 minutos
        AS-)M: enviarEnlace(correo, enlace), en segundo plano
        M-)U: correo con /magic?token=…
        AS->>B: access_log: sent
    else no hay
        AS->>B: access_log: refused
    end
    AS-->>P: 202, siempre con el mismo texto
    Note over AS,P: la respuesta y su demora son iguales salga o no el correo:<br/>no se revela quién es deudor ni quién es personal

    U->>P: abre el enlace del correo
    P->>AC: POST /api/auth/verify {token}
    AC->>AS: entrarConEnlace(token, ip)
    AS->>B: magic_links por huella: que exista, sin usar y sin vencer
    AS->>B: lo consume y anota granted
    alt el enlace era de un deudor
        AS->>AS: sesión DEBTOR con su RUT
    else era de personal
        AS->>B: staff_users: que siga habilitado, y anota el ingreso
        AS->>AS: sesión CREDITOR con el RUT de su empresa,<br/>o ADMIN sin RUT
    end
    AS-->>P: JWT y cookie de renovación, como en la secuencia 1
    P-->>U: su portal: /app, /databridge o /admin
```

---

## 3. Renovar y cerrar la sesión

El JWT dura 15 minutos y no se puede revocar. Lo que mantiene a alguien adentro es la llave de renovación, y
cada llave sirve **una sola vez**: al usarla se jubila y deja otra en la misma familia.

```mermaid
sequenceDiagram
    participant P as Portal
    participant AC as AuthController
    participant AS as AuthService
    participant SS as SessionService
    participant B as tb_auth

    Note over P: el JWT venció, o se recargó la página
    P->>AC: POST /api/auth/refresh, con la cookie tb_renovacion
    AC->>AS: renovar(llave, ip)
    AS->>SS: renovar(llave)
    SS->>B: sessions por huella, bloqueando la fila
    alt no existe, está revocada o venció
        SS-->>AC: 401 La sesión no es válida
        AC-->>P: 401, y borra la cookie
    else ya se usó hace menos de 30 segundos
        SS-->>AC: 409: otra pestaña la acaba de renovar
        AC-->>P: 409, y deja la cookie donde está
        P->>AC: reintenta: el navegador ya tiene la llave nueva
    else ya se usó hace más: alguien más la tiene
        SS->>B: revoca la familia entera
        SS-->>AC: 401 La sesión se cerró por seguridad
        AC-->>P: 401, y borra la cookie
    else es la llave vigente
        SS->>B: la marca como usada
        SS->>B: emite la siguiente, en la misma familia<br/>y con el mismo vencimiento
        SS-->>AS: Renovada(llave nueva, rol, rut, correo)
        opt la sesión es de un usuario o de un admin
            AS->>B: staff_users: que siga habilitado y con el mismo rol
            Note over AS: si no, revoca la familia y responde 401
        end
        AS-->>AC: Sesion(JWT nuevo, usuario, llave nueva)
        AC-->>P: 200 con el JWT, y la cookie con la llave nueva
    end

    Note over P: cerrar sesión
    P->>AC: POST /api/auth/logout, con la cookie
    AC->>AS: cerrar(llave)
    AS->>SS: cerrar(llave)
    SS->>B: revoca la familia entera
    AC-->>P: 204, y borra la cookie
```

Renovar no alarga la sesión: la llave nueva hereda el vencimiento de la familia (7 días desde que se entró).

---

## 4. Entregar una cartera por la API

El escenario E1. El sistema de una empresa entrega su cartera, DataBridge decide deuda por deuda y, **después de
guardar**, invita a cada deudor.

```mermaid
sequenceDiagram
    participant S as Sistema de la empresa
    participant IC as IntegracionController
    participant AK as ApiKeyService
    participant CI as CarteraIntakeService
    participant ES as EventosService
    participant B as tb_debt
    participant IV as InvitacionService
    participant AU as ms-auth
    participant C as Correo del deudor

    S->>IC: POST /api/v1/carteras, con Authorization: Bearer tbk_…
    IC->>AK: autenticar(clave)
    AK->>B: api_keys por huella, sin revocar
    AK-->>IC: la empresa dueña de la clave
    Note over IC,AK: quién envía sale de la clave, nunca del cuerpo.<br/>Sin clave válida: 401 no_autorizado
    IC->>CI: recibir(emisor, cartera, api)
    CI->>B: el acreedor, el mandato vigente a la fecha de corte y la campaña
    CI->>B: ¿ya llegó un lote con ese id de ese emisor?
    alt ya llegó, con el mismo contenido
        CI-->>S: la misma respuesta de la primera vez
    else ya llegó, con otro contenido
        CI-->>S: 409 lote_id_reutilizado
    else es nuevo
        CI->>B: batches: el lote, con la huella de su contenido
        loop cada deuda de la cartera
            alt cumple: RUT válido, cargos vencidos, 30 días de mora o más, tasa bajo el tope
                CI->>B: debtors, debts, debt_charges e installments
                CI->>B: debt_events: registered o updated
                CI-)IV: publica DeudaEnCobranza(id)
            else no cumple
                Note over CI: queda rechazada con su motivo:<br/>bajo_umbral_mora, tasa_sobre_maxima, rut_invalido…
            end
        end
        CI->>ES: publicarDeLote(lote.procesado)
        ES->>B: outbox: un evento por suscriptor
        CI->>B: batches: los totales y la respuesta
        CI-->>S: 200: recibidas, aceptadas, rechazadas y el resultado de cada una
    end

    Note over IV: después de confirmar la transacción, en segundo plano
    IV->>B: la deuda sigue abierta y el deudor tiene correo
    IV->>B: LimiteDeContacto: gestiones de los últimos 7 días
    opt la ley deja escribirle
        IV->>AU: POST /internal/codigos, con la clave interna
        AU->>AU: guarda la huella del código, vigente 24 horas
        AU-)C: correo con el código: sin monto y sin enlace
        IV->>B: debt_events: code_sent
    end
```

Una cartera cargada **por archivo** desde el portal (`POST /api/debts/cartera`) hace el mismo recorrido:
`CargaService` convierte la planilla con `CarteraCsv` y llama al mismo `CarteraIntakeService.recibir`.

---

## 5. Simular y aceptar un convenio

El escenario E4. La simulación no guarda nada; aceptar sí.

```mermaid
sequenceDiagram
    actor D as Deudor
    participant P as Portal
    participant DC as DebtController
    participant DS as DebtService
    participant RS as RepactationService
    participant ES as EventosService
    participant B as tb_debt

    D->>P: elige en cuántas cuotas
    P->>DC: GET /api/debts/{id}/simulate?months=n
    DC->>DS: simulate(usuario, id, n)
    DS->>B: la deuda, que tiene que ser de su RUT
    DS->>B: sus cargos y sus cuotas pendientes
    Note over DS: Intereses.deMora: la mora de hoy,<br/>con la tasa que pactó el acreedor
    DS->>RS: simulate(capital, mora, tasa, moneda, n, desde)
    Note over RS: de 3 a 24 cuotas. Con tasa, sistema francés: cuotas iguales,<br/>cada una con su interés. Sin tasa, partes iguales
    RS-->>DS: RepactPlan: lo repactado, la cuota y el total a pagar
    DS-->>P: el plan, con el enlace para aceptarlo

    D->>P: acepta
    P->>DC: POST /api/debts/{id}/repact {months}
    DC->>DS: applyRepact(usuario, id, n)
    Note over DS: no se repacta una deuda pagada,<br/>retirada ni en reclamo
    DS->>RS: simulate(…), otra vez: el plan no viene del navegador
    DS->>B: repactations: el convenio anterior queda reemplazado
    DS->>B: repactations: el convenio nuevo, con su tasa y lo repactado
    DS->>B: installments: las pendientes pasan a anuladas
    DS->>B: installments: las cuotas del convenio, cada una con su interés
    DS->>B: debts: status = repacted
    DS->>B: debt_events: repacted
    DS->>ES: publicar(repactacion.aceptada)
    ES->>B: outbox: un evento por suscriptor
    DS-->>P: la deuda, ya en convenio
    P-->>D: sus cuotas y sus fechas
```

---

## 6. Pagar con Khipu

El escenario E3, y la función principal. **El navegador nunca dice cuánto hay que pagar ni si el pago salió
bien**: el monto lo pregunta ms-payments a ms-debt, y la aprobación la confirma con Khipu, de servidor a
servidor.

```mermaid
sequenceDiagram
    actor D as Deudor
    participant P as Portal
    participant PC as PaymentController
    participant PS as PaymentService
    participant DB as ms-debt
    participant K as Khipu
    participant B as tb_payments

    D->>P: Pagar: el saldo o las cuotas que marcó
    P->>PC: POST /api/payments/checkout {debtId, installmentIds, gateway}
    PC->>PS: checkout(usuario, pedido)
    PS->>DB: GET /internal/debts/{id}?installmentIds, con la clave interna
    DB-->>PS: monto (capital más mora de hoy, menos descuento),<br/>RUT del deudor y del acreedor, y las cuotas
    Note over DB: las cuotas tienen que ser<br/>las que vencen primero
    Note over PS: la deuda tiene que ser del RUT de la sesión
    PS->>B: ¿hay otro pago abierto de esta deuda?
    alt uno está en verificación en la pasarela
        PS-->>P: 409 Tienes un pago en verificación
    else uno quedó abierto sin pagar
        PS->>K: lo anula
        PS->>B: ese pago queda vencido
    end
    opt la deuda es en UF
        PS->>B: uf_values: la UF de hoy, y fija los pesos
    end
    PS->>B: payments: created, y payment_events: created
    PS->>K: crea el cobro: transacción, monto, vuelta y vencimiento
    K-->>PS: payment_id y la página de pago
    PS->>B: payments: guarda el id de Khipu
    PS-->>P: el pago, con su enlace
    P-->>D: lo lleva a Khipu

    D->>K: paga con una transferencia desde su banco
    K-->>D: lo devuelve al portal, sin decir cómo quedó
    D->>P: la página del resultado
    P->>PC: POST /api/payments/public/{id}/verificar?sig=…
    PC->>PS: verificar(id, firma)
    Note over PS: la firma del enlace tiene que calzar
    PS->>K: ¿en qué va ese cobro?
    K-->>PS: estado, monto y transacción
    alt Khipu lo concilió, y el monto y la transacción calzan
        PS->>B: payments: paid, y payment_events: paid, con lo que dijo Khipu
        PS->>B: debt_notifications: el aviso para ms-debt, pendiente
        PS-->>P: pagado
        P-->>D: Pago aprobado
    else todavía lo está verificando
        PS-->>P: sigue abierto
        Note over P: vuelve a preguntar
    else no se pagó, o no es el cobro que se abrió
        PS->>B: payments: failed
        PS-->>P: no se pagó
    end
```

Si el deudor cierra la ventana antes de volver, el pago igual se registra: `ConciliacionPasarelas` le pregunta a
Khipu por los cobros abiertos cada 10 segundos, y hace exactamente la misma verificación. Lo que sigue al pago
está en la [secuencia 9](#9-el-aviso-del-pago-de-ms-payments-a-quien-entregó-la-cartera).

---

## 7. Pagar con Webpay

La misma idea con otra forma: en Webpay el deudor vuelve con un `token_ws`, y la transacción solo se cobra si
ms-payments la **confirma** con Transbank.

```mermaid
sequenceDiagram
    actor D as Deudor
    participant P as Portal
    participant G as Gateway
    participant PS as PaymentService
    participant DB as ms-debt
    participant T as Transbank
    participant B as tb_payments

    D->>P: Pagar, con Webpay
    P->>PS: POST /api/payments/checkout
    PS->>DB: el monto y a quién se le debe
    PS->>B: payments: created
    PS->>T: crea la transacción: orden, sesión, monto y dirección de vuelta
    T-->>PS: el token
    PS->>B: payments: guarda el token
    PS-->>P: el enlace /api/payments/public/{id}/webpay?sig=…
    P->>PS: abre ese enlace
    PS->>B: payments: anota que ya se abrió
    PS-->>D: una página que envía el token a Webpay por POST
    Note over PS: el token se manda una sola vez. Abrir el enlace de nuevo<br/>explica que Webpay ya se abrió, en vez de repetirlo

    D->>T: paga en Webpay
    T-->>D: lo devuelve a /api/payments/public/webpay/retorno
    D->>G: llega con token_ws, o con TBK_TOKEN si anuló
    Note over G: RetornoDePasarela le quita el Origin:<br/>es la página de Transbank la que hace volver al navegador
    G->>PS: retornoWebpay(token_ws, TBK_TOKEN, orden, sesión)
    alt solo token_ws: pagó
        PS->>T: confirma la transacción
        T-->>PS: estado, código de respuesta, monto y orden
        alt AUTHORIZED, código 0, y el monto y la orden calzan
            PS->>B: payments: paid, payment_events: paid<br/>y debt_notifications: pendiente
        else no calza
            PS->>B: payments: failed
        end
    else TBK_TOKEN: anuló, o el formulario falló
        PS->>B: payments: failed
    else solo orden y sesión: se le acabó el tiempo
        Note over PS: la sesión tiene que ser la de ese cobro:<br/>la orden se puede adivinar, la sesión no
        PS->>B: payments: failed
    end
    PS-->>D: 302 a la página del resultado, en el portal
```

Si el deudor paga y cierra la ventana antes de volver, `ConciliacionPasarelas` le pregunta a Transbank por el
cobro y lo confirma desde ahí. Un cobro de Webpay abandonado vence a los 15 minutos en el ambiente de
integración de Transbank y a los 9 en producción.

---

## 8. Pagar con Mercado Pago

Checkout Pro. Mercado Pago no siempre puede devolver al deudor ni avisar (en local descarta las direcciones que
no son `https`), así que el estado nunca se toma de lo que llega: **siempre se le pregunta**.

```mermaid
sequenceDiagram
    actor D as Deudor
    participant P as Portal
    participant PS as PaymentService
    participant DB as ms-debt
    participant MP as Mercado Pago
    participant CP as ConciliacionPasarelas
    participant B as tb_payments

    D->>P: Pagar, con Mercado Pago
    P->>PS: POST /api/payments/checkout
    PS->>DB: el monto y a quién se le debe
    PS->>B: payments: created
    PS->>MP: crea la preferencia: monto, referencia y vencimiento
    MP-->>PS: el id de la preferencia y su página de pago
    PS->>B: payments: guarda el id de la preferencia
    PS-->>P: el enlace de pago
    P-->>D: lo lleva a Mercado Pago
    D->>MP: paga

    par el deudor vuelve
        MP-->>D: lo devuelve a /api/payments/public/mercadopago/retorno
        D->>PS: retornoMercadoPago(payment_id, estado, referencia)
        PS->>MP: consulta ese pago
    and Mercado Pago avisa
        MP-)PS: POST /api/payments/public/mercadopago/aviso
        PS->>MP: consulta ese pago: el aviso solo dice cuál mirar
    and nadie vuelve ni avisa
        loop cada 10 segundos
            CP->>PS: conciliarPendientes()
            PS->>MP: consulta la preferencia y su pago
        end
    end
    MP-->>PS: estado, monto y referencia
    alt aprobado
        PS->>B: payments: paid, payment_events: paid<br/>y debt_notifications: pendiente
    else rechazado
        Note over PS: el cobro sigue abierto: en Checkout Pro<br/>el deudor puede reintentar con otra tarjeta
    else la preferencia venció sin pagarse
        PS->>B: payments: expired
    end
```

Por los tres caminos el pago se registra **una sola vez**: `payments` no deja repetir el par pasarela e id de la
transacción, y confirmar un pago ya pagado no hace nada.

---

## 9. El aviso del pago: de ms-payments a quien entregó la cartera

Lo que pasa después de cualquier pago confirmado. Hay **dos bandejas de salida**, una en cada servicio, y las
dos se escriben en la misma transacción que el hecho: si un sistema está caído, el aviso espera, no se pierde.

```mermaid
sequenceDiagram
    participant ND as NotificationDispatcher
    participant EP as EventPublisher
    participant BP as tb_payments
    participant Q as RabbitMQ
    participant RL as RabbitPaymentListener
    participant DS as DebtService
    participant ES as EventosService
    participant BD as tb_debt
    participant ED as EventDispatcher
    participant S as Sistema de la empresa

    loop cada 15 segundos, en ms-payments
        ND->>BP: debt_notifications pendientes
        ND->>BP: el pago de cada una
        ND->>EP: publicar(PagoConfirmado)
        alt con RabbitMQ
            EP-)Q: tbridge.pagos, con la llave pago.confirmado
            Q-)RL: cola ms-debt.pagos-confirmados
            RL->>DS: onPagoConfirmado(aviso)
        else sin RabbitMQ
            EP->>DS: POST /internal/events/pago-confirmado, con la clave interna
        end
        alt se entregó
            ND->>BP: debt_notifications: delivered
        else falló
            ND->>BP: suma el intento y fija cuándo reintenta
        end
    end

    DS->>BD: ¿ese pago ya se abonó?
    Note over DS: un aviso repetido no abona dos veces
    DS->>BD: installments: las cuotas que dice el aviso, pagadas
    DS->>BD: debt_events: payment_applied, con el capital,<br/>el interés y el descuento aparte
    DS->>ES: publicar(pago.confirmado)
    ES->>BD: outbox: un evento por suscriptor
    opt el saldo quedó en cero
        DS->>BD: debts: status = paid, y debt_events: settled
        DS->>ES: publicar(deuda.saldada)
        ES->>BD: outbox
    end

    loop cada 15 segundos, en ms-debt
        ED->>BD: outbox: hasta 100 eventos pendientes
        ED->>S: POST a la dirección suscrita, con X-Evento,<br/>X-Evento-Id, X-Timestamp y X-Firma
        Note over ED,S: X-Firma = v1= HMAC-SHA256(secreto, timestamp.cuerpo)
        alt respondió 2xx
            ED->>BD: outbox: delivered
        else falló o no respondió
            ED->>BD: suma el intento y fija cuándo reintenta
        end
    end
```

Por el mismo `EventDispatcher` salen todos los eventos del contrato: `lote.procesado`, `repactacion.aceptada`,
`pago.confirmado`, `deuda.saldada`, `deuda.disputada`, `deuda.reanudada`, `deuda.retirada` y `campana.avance`.

---

## 10. La campaña le escribe al deudor

El escenario E2. La estrategia la define la agencia; DataBridge la ejecuta dentro de lo que permite la ley.

```mermaid
sequenceDiagram
    participant R as El reloj
    participant CS as ContactoService
    participant LC as LimiteDeContacto
    participant AC as AuthClient
    participant AU as ms-auth
    participant C as Correo del deudor
    participant B as tb_debt

    loop cada 15 minutos
        R->>CS: pasar()
        alt es domingo, feriado o fuera de 8:00 a 20:00
            Note over CS: no hace nada
        else es hora de contacto
            CS->>B: campaigns en curso, dentro de sus fechas y con correo
            loop cada campaña
                CS->>B: sus deudas abiertas
                CS->>B: debt_events: cuántos correos lleva cada una
                loop cada deuda
                    Note over CS: ¿le quedan intentos?<br/>¿llegó el día según la cadencia?
                    CS->>LC: permite(deudor, ahora)
                    LC->>B: gestiones al deudor en los últimos 7 días
                    LC-->>CS: sí, si lleva menos de 2 y pasaron 2 días desde la última
                    opt le toca y la ley lo deja
                        CS->>AC: emitirCodigo(rut, correo, acreedor)
                        AC->>AU: POST /internal/codigos, con la clave interna
                        AU-)C: correo con el código: sin monto y sin enlace
                        CS->>B: debt_events: code_sent, con la campaña y el número de correo
                    end
                end
            end
        end
    end
```

Un correo que no pudo salir no se pierde: en la pasada siguiente se vuelve a preguntar, y sale en cuanto la ley
lo deja. Una deuda repactada, pagada, en reclamo o retirada deja de recibir los correos de la campaña.

El **recordatorio de cuotas** (`RecordatorioService`, todos los días a las 9:00) sigue el mismo camino con otra
pregunta: las cuotas pendientes que vencen en los próximos 3 días, de deudores que no apagaron el recordatorio.
Pasa por el mismo `LimiteDeContacto` y por el mismo `POST /internal/codigos`, que en ese caso manda el correo
del recordatorio con la fecha de la cuota.

**A quién le llega de verdad.** En ms-auth, cada correo pasa por `ModoDePrueba` antes de salir. Con el buzón de
prueba de Docker, o en producción, llega a su destinatario. Con un servidor real y el modo de prueba activo,
llega directo solo si la dirección está en `CORREO_SOLO_A`; si no, va al buzón de `CORREO_DESVIAR_A` con el
destinatario original en el asunto, o no sale. Así la campaña no les escribe a los correos inventados de la
demo.

---

## 11. Reclamar una deuda y resolver el reclamo

El escenario E5. El reclamo lo abre el deudor y lo resuelve la empresa que cobra.

```mermaid
sequenceDiagram
    actor D as Deudor
    actor U as Usuario de la empresa
    participant DC as DebtController
    participant DI as DisputaService
    participant ES as EventosService
    participant B as tb_debt

    D->>DC: POST /api/debts/{id}/disputa {motivo, detalle}
    DC->>DI: disputar(usuario, id, pedido)
    Note over DI: solo el deudor, sobre una deuda suya,<br/>abierta o en convenio
    DI->>B: debts: status = disputed
    DI->>B: debt_events: disputed, con el motivo y lo que escribió
    DI->>ES: publicar(deuda.disputada)
    ES->>B: outbox
    Note over ES: al sistema de la empresa viaja el motivo,<br/>no el texto del deudor
    DI-->>D: la deuda, en revisión

    U->>DC: POST /api/debts/{id}/disputa/resolucion {resultado, nota}
    DC->>DI: resolver(usuario, id, pedido)
    Note over DI: solo la empresa, sobre una deuda<br/>de su cartera que esté en reclamo
    alt reanudar
        DI->>B: installments: ¿le quedan cuotas de un convenio?
        DI->>B: debts: status = repacted si le quedan, open si no
        DI->>B: debt_events: updated, disputa_rechazada
        DI->>ES: publicar(deuda.reanudada)
    else retirar
        DI->>B: debts: status = withdrawn, disputa_resuelta
        DI->>B: installments: las pendientes pasan a anuladas
        DI->>B: debt_events: withdrawn
        DI->>ES: publicar(deuda.retirada)
    end
    ES->>B: outbox
    DI-->>U: la deuda, ya resuelta
```

---

## 12. Preguntarle al asistente

ms-ai no tiene base ni acceso propio a las deudas: las pide a ms-debt **con la sesión del deudor**, así que no
puede ver nada que él no vea.

```mermaid
sequenceDiagram
    actor D as Deudor
    participant P as Portal (Chatbot)
    participant G as Gateway
    participant CC as ChatController
    participant AS as AsistenteService
    participant DC as DeudasClient
    participant MD as ms-debt
    participant L as LlmService
    participant X as LLM
    participant ML as MotorLocal

    D->>P: escribe su pregunta
    P->>G: POST /api/ai/chat {message, messages}, con el JWT
    G->>CC: la enruta a ms-ai
    Note over CC: sin Authorization: 401 No autorizado
    CC->>AS: responder(pregunta, authorization)
    AS->>DC: deudasDelDeudor(authorization)
    DC->>MD: GET /api/debts, con el JWT del deudor
    MD-->>DC: sus deudas, y solo las suyas
    AS->>ML: sentimiento(último mensaje)
    ML-->>AS: el ánimo: para ajustar el tono
    AS->>L: responder(conversación, deudas, ánimo)
    alt hay un LLM configurado y responde
        L->>X: la conversación, con las deudas como contexto
        X-->>L: la respuesta
        L-->>AS: el texto
    else no hay, o falló
        L-->>AS: nada
        AS->>ML: responder(mensaje, deudas)
        ML-->>AS: la respuesta por reglas
    end
    AS-->>CC: Respuesta: el texto, de dónde salió y el ánimo
    CC-->>P: 200
    P-->>D: la respuesta
```

El asistente solo lee: no tiene ningún endpoint que cambie una deuda.

---

## 13. El admin abre su panel

Entra con su enlace ([secuencia 2](#2-entrar-con-un-enlace-al-correo), por `/admin/login`). La portada le
pregunta a cada servicio si responde, con su sesión.

```mermaid
sequenceDiagram
    actor A as Admin de DataBridge
    participant P as Portal (AdminInicio)
    participant G as Gateway
    participant AU as ms-auth
    participant DE as ms-debt
    participant PA as ms-payments

    A->>P: abre /admin
    Note over P: Guard: la sesión tiene que ser ADMIN.<br/>A otro rol lo devuelve a su propio portal
    par a la vez
        P->>G: GET /api/admin/auth/estado
        G->>AU: ruta panel-ms-auth
        AU-->>P: 200 {servicio: ms-auth, ok: true}
    and
        P->>G: GET /api/admin/debt/estado
        G->>DE: ruta panel-ms-debt
        DE-->>P: 200 {servicio: ms-debt, ok: true}
    and
        P->>G: GET /api/admin/payments/estado
        G->>PA: ruta panel-ms-payments
        PA-->>P: 200 {servicio: ms-payments, ok: true}
    end
    P-->>A: qué servicios responden

    Note over AU,PA: en cada servicio, JwtAuthFilter lee el rol del JWT<br/>y SecurityConfig exige ADMIN en todo /api/admin
    alt la sesión es de un usuario o de un deudor
        AU-->>P: 403 No tienes permiso para esto
    else sin sesión
        AU-->>P: 401 No autorizado
    end
```

La regla es de la ruta entera, no de cada endpoint: uno nuevo bajo `/api/admin` no puede quedar abierto por
olvido. Y vale al revés: con una sesión `ADMIN`, ms-debt y ms-payments responden `403` a todo lo que no sea del
panel.

---

## 14. Una empresa conecta su sistema

Nada de esto se configura a mano en DataBridge: la empresa emite su clave, y su sistema hace el resto.

```mermaid
sequenceDiagram
    actor U as Usuario de la empresa
    participant S as Sistema de la empresa
    participant CL as ClaveController
    participant IC as IntegracionController
    participant AK as ApiKeyService
    participant MS as MandatoService
    participant ES as EventosService
    participant B as tb_debt

    U->>CL: POST /api/claves {nombre}, desde el portal
    CL->>AK: emitir(empresa, nombre)
    AK->>B: api_keys: la huella y el prefijo de la clave
    AK-->>U: la clave tbk_…, que se muestra una sola vez
    U->>S: la pega en su sistema

    S->>IC: GET /api/v1/cuenta
    IC->>AK: autenticar(clave)
    IC-->>S: de quién es la clave y con quién se conectó

    S->>IC: POST /api/v1/suscripciones {url, eventos}
    IC->>ES: suscribir(empresa, pedido)
    ES->>B: subscriptions: la dirección y el secreto, cifrado
    ES-->>S: el secreto para verificar las firmas, una sola vez

    S->>IC: POST /api/v1/mandatos
    IC->>MS: registrarMandato(agencia, pedido)
    MS->>B: organizations: el acreedor, si DataBridge no lo conocía
    MS->>B: mandates: desde cuándo cobra por él, y con qué tope de descuento
    MS-->>S: el mandato

    S->>IC: POST /api/v1/campanas
    IC->>MS: registrarCampana(empresa, pedido)
    Note over MS: tiene que haber mandato vigente, y el descuento<br/>no puede pasar del tope del mandato
    MS->>B: campaigns: fechas, cadencia, intentos, descuento y estado
    MS-->>S: la campaña

    Note over S,IC: desde aquí entrega carteras: secuencia 4
```

---

## 15. La UF del día

Una deuda en UF se cobra en pesos, con la UF del día en Chile. Ese valor se guarda: un pago tiene que poder
reconstruirse después, y el cobro no puede depender de que la fuente esté arriba.

```mermaid
sequenceDiagram
    participant R as El reloj
    participant UL as UfLoader
    participant BC as BancoCentralClient
    participant X as Banco Central
    participant O as Operación
    participant IC as InternalController
    participant US as UfService
    participant PS as PaymentService
    participant B as tb_payments

    Note over R,UL: al arrancar, y todos los días a las 9:30
    R->>UL: todosLosDias()
    alt hay credenciales del Banco Central
        UL->>BC: obtener(desde, hasta)
        BC->>X: la serie de la UF
        X-->>BC: los valores
        UL->>B: uf_values: uno por día
    else no hay
        Note over UL: no carga nada
    end

    opt la fuente no responde, o no hay credenciales
        O->>IC: POST /internal/uf {dia, valor}, con la clave interna
        IC->>US: cargarAMano(día, valor)
        US->>B: uf_values
    end

    Note over PS: al abrir un cobro de una deuda en UF
    PS->>US: delDia(hoy)
    US->>B: uf_values de hoy
    alt hay valor
        US-->>PS: la UF
        PS->>US: aPesos(monto, UF)
        Note over PS: los pesos quedan fijos en el pago:<br/>se confirme hoy o mañana, se cobra lo mismo
    else no hay
        US-->>PS: 503: el cobro en UF no puede continuar
    end
```

---

## 16. El admin da de alta una empresa y a su gente

Una empresa vive en ms-debt y sus personas en ms-auth, unidas por el RUT. Por eso dar de alta son **dos pasos**,
uno en cada servicio, y ms-auth le pregunta a ms-debt si la empresa existe antes de darle una persona.

```mermaid
sequenceDiagram
    actor A as Admin de DataBridge
    participant P as Portal (AdminEmpresas)
    participant DC as AdminController (ms-debt)
    participant ES as EmpresaService
    participant BD as tb_debt
    participant AC as AdminController (ms-auth)
    participant PS as PersonalService
    participant BA as tb_auth
    participant M as MailService
    actor U as La persona nueva

    A->>P: los datos de la empresa y de su primera persona
    P->>DC: POST /api/admin/debt/empresas {rut, razonSocial, nombre, tipo}
    DC->>ES: crear(pedido, el correo del admin de la sesión)
    Note over ES: el RUT tiene que tener su dígito verificador
    ES->>BD: ¿ya hay una empresa con ese RUT?
    alt el RUT no es válido, o ya existe
        ES-->>P: 400 o 409, con el motivo
    else es nueva
        ES->>BD: organizations: la empresa, como agencia o como acreedor
        ES->>BD: admin_log: empresa_creada, quién y cuándo
        ES-->>P: 201 con la empresa
        P->>AC: POST /api/admin/auth/empresas/{rut}/personal {correo, nombre}
        AC->>PS: crearUsuario(rut, pedido, admin)
        PS->>DC: GET /internal/organizaciones/{rut}, con la clave interna
        DC-->>PS: la empresa, o 404
        Note over PS: sin la empresa en ms-debt, o si ms-debt<br/>no responde, no se crea a nadie
        PS->>BA: staff_users por correo: que no tenga ya una cuenta
        PS->>BA: staff_users: la persona, como usuario de esa empresa
        Note over PS: el rol lo decide la ruta: bajo una empresa<br/>siempre es usuario, nunca admin
        PS->>BA: admin_log: persona_creada, quién y cuándo
        PS->>BA: magic_links: la huella de su enlace, vigente 24 horas
        PS-)M: enviarInvitacion(correo, nombre, a qué, enlace), en segundo plano
        M-)U: correo con su enlace, y por dónde entrar las veces siguientes
        PS-->>P: 201 con la persona
        P-->>A: la ficha de la empresa, con su gente y su historia
    end

    U->>P: abre el enlace de la invitación
    Note over P,BA: sigue como en la secuencia 2: entra al portal de empresas,<br/>con la cartera vacía, y puede emitir la clave de API de su sistema

    Note over A,BA: quitarle el acceso a una persona
    A->>P: Quitar acceso
    P->>AC: POST /api/admin/auth/personal/{id}/deshabilitar
    AC->>PS: deshabilitar(id, admin)
    Note over PS: un admin no se lo puede quitar a sí mismo
    PS->>BA: staff_users: disabled_at
    PS->>BA: magic_links: sus enlaces sin abrir quedan usados
    PS->>BA: sessions: todas sus llaves quedan revocadas
    PS->>BA: admin_log: persona_deshabilitada
    PS-->>P: la persona, sin acceso
```

Si el segundo paso falla (el correo ya tenía una cuenta, o ms-debt no respondió), la empresa ya quedó
registrada: el portal lleva al admin a su ficha, que dice qué pasó, y desde ahí se le da acceso a la persona.
Quitarle el acceso a alguien no espera a que su sesión venza: sus llaves de renovación se revocan en el acto, y
lo único que le queda es el JWT vigente, que dura a lo más quince minutos.

Otro admin se da de alta igual, por `POST /api/admin/auth/admins`, sin el paso de ms-debt: un admin no es de
ninguna empresa.
