# Diagramas de procesos

Vista de procesos del [modelo 4+1](README.md), por el lado del negocio: **cómo va una deuda** desde que alguien
se atrasa hasta que la plata vuelve al acreedor, qué decide cada sistema cuando llega la cartera, cómo
DataBridge ejecuta una campaña, y qué corre solo. Los pasos de cada servicio, clase por clase, están en las
[secuencias](secuencia.md).

Los diagramas de proceso usan la notación de **BPMN**: cada carril es un participante, el círculo verde es el
inicio, el rojo doble el fin, el rombo una decisión, y la flecha punteada un mensaje de un sistema a otro.

**La cadena con APOFYX y Patrimonio** necesita la entrega por API encendida (`INTEGRACION_API=true`): por
omisión, los deudores entran por campaña desde el portal, como cuenta la
[secuencia de ms-debt del README](../../README.md#83-secuencias).

## El proceso completo, en BPMN (*to-be*)

Así va una deuda desde que alguien se atrasa hasta que la plata vuelve al acreedor, tal como
funciona hoy:

```mermaid
flowchart TB
    classDef inicio fill:#d4edda,stroke:#2e7d32,color:#1b5e20
    classDef fin fill:#f8d7da,stroke:#c62828,color:#7f0000
    classDef decision fill:#fff3cd,stroke:#b8860b,color:#5d4037
    classDef tarea fill:#e8eef7,stroke:#3f5f8a,color:#1a2a40

    subgraph PAT["Acreedor (Patrimonio)"]
        P0(("Fin de mes")):::inicio --> P1["Genera los cargos<br/>del mes"]:::tarea --> P2["Emite la cartera:<br/>todos sus clientes,<br/>deban o no"]:::tarea
        P9["Abona el capital y anota<br/>el interés y lo condonado"]:::tarea --> P10((("Cuenta<br/>al día"))):::fin
    end

    subgraph APX["Agencia (APOFYX)"]
        A1["Revisa cada deuda:<br/>mora, al día,<br/>más de 120 días"]:::tarea --> A2["La asigna a<br/>la campaña en curso"]:::tarea
        A8["Pone la deuda al día<br/>y le reenvía el aviso<br/>al acreedor"]:::tarea
    end

    subgraph DB["DataBridge"]
        D1{"¿30 días de mora<br/>o más, y tasa<br/>bajo el tope?"}:::decision
        D1 -->|no| DX((("Rechazada,<br/>con su motivo"))):::fin
        D1 -->|sí| D2["Registra la deuda y<br/>le manda la invitación"]:::tarea --> D3["Le sigue escribiendo<br/>según la campaña"]:::tarea
        D5["Cobra, arma el convenio<br/>o abre el reclamo"]:::tarea --> D6["Avisa lo que pasó,<br/>firmado"]:::tarea
    end

    subgraph DEU["Deudor"]
        U1["Recibe un correo<br/>con su código"]:::tarea --> U2["Entra y ve lo que debe,<br/>con la mora de hoy<br/>y el descuento, si hay"]:::tarea --> U3{"¿Qué hace?"}:::decision
    end

    P2 -. "cartera" .-> A1
    A2 -. "cartera + campaña" .-> D1
    D2 -. "correo" .-> U1
    D3 -. "correo" .-> U1
    U3 -->|"paga, repacta<br/>o reclama"| D5
    D6 -. "aviso" .-> A8
    A8 -. "aviso" .-> P9
```

| Paso | Quién | Qué pasa |
| --- | --- | --- |
| 1 | Acreedor | A fin de mes genera los cargos y entrega la **cartera**: todos sus clientes con contrato, deban o no, con la tasa de interés de cada uno |
| 2 | APOFYX | Revisa cada deuda (mora, al día, más de 120 días), la asigna a la **campaña** en curso y se la pasa a DataBridge |
| 3 | DataBridge | Acepta las deudas con 30 días de mora o más y una tasa bajo el tope, y le manda al deudor una **invitación**: un código, sin monto ni enlace |
| 4 | DataBridge | Si el deudor no entra ni paga, **le sigue escribiendo** los días de la campaña, dentro de lo que permite la ley |
| 5 | Deudor | Entra con su RUT y el código, ve lo que debe con la mora del día (y el descuento, si la campaña lo trae y paga todo de una vez) y decide: **pagar**, **repactar** o **reclamar** |
| 6 | DataBridge | Cobra con la pasarela, arma el convenio o abre el reclamo, y **avisa** lo que pasó, firmado |
| 7 | APOFYX | Pone su cartera al día y le **reenvía el aviso** al acreedor, firmado por ella |
| 8 | Acreedor | Deja los cargos pagados y anota aparte el interés cobrado y el condonado, sin saber que detrás está DataBridge |

## Flujo: la cartera de cada mes

Las decisiones que toma cada sistema cuando llega la cartera (el escenario E1):

```mermaid
flowchart TB
    classDef inicio fill:#d4edda,stroke:#2e7d32,color:#1b5e20
    classDef fin fill:#f8d7da,stroke:#c62828,color:#7f0000
    classDef decision fill:#fff3cd,stroke:#b8860b,color:#5d4037
    classDef tarea fill:#e8eef7,stroke:#3f5f8a,color:#1a2a40

    subgraph PAT["Acreedor"]
        P0(("Fin de mes")):::inicio --> P1["Genera los cargos del mes<br/>(nunca dos veces el mismo)"]:::tarea
        P1 --> P2["Emite la cartera<br/>con todos sus clientes"]:::tarea
        P2 --> P3["La envía a la agencia"]:::tarea
        P4["Guarda la respuesta,<br/>deuda por deuda"]:::tarea --> P5((("Cartera<br/>entregada"))):::fin
    end

    subgraph APX["APOFYX"]
        A0{"¿Esta cartera<br/>ya llegó antes?"}:::decision
        A0 -->|sí| AR["Responde lo mismo<br/>que la primera vez"]:::tarea
        A0 -->|no| A1{"¿El cliente debe algo?"}:::decision
        A1 -->|"no (al día)"| A2["Si lo tenía en cobranza, la cierra:<br/>pagó directo al acreedor"]:::tarea
        A1 -->|sí| A3{"¿Más de 120 días<br/>de mora?"}:::decision
        A3 -->|sí| A4["Se la devuelve al acreedor:<br/>fuera de mandato"]:::tarea
        A3 -->|no| A5["Calcula la mora y el tramo,<br/>y la acepta"]:::tarea
        A5 --> A6{"¿Hay una sola campaña<br/>en curso?"}:::decision
        A6 -->|no| A7["Queda esperando campaña:<br/>el personal la asigna"]:::tarea
        A6 -->|sí| A8["La reenvía a DataBridge,<br/>con el mandato y la campaña"]:::tarea
        A7 --> A8
    end

    subgraph DB["DataBridge"]
        D0{"¿El mandato<br/>está vigente?"}:::decision
        D0 -->|no| DX["Rechaza la cartera"]:::tarea
        D0 -->|sí| D1{"¿30 días de mora<br/>o más?"}:::decision
        D1 -->|no| D2["Rechaza esa deuda:<br/>bajo_umbral_mora"]:::tarea
        D1 -->|sí| D3{"¿La tasa de interés<br/>está bajo el tope?"}:::decision
        D3 -->|no| D4["Rechaza esa deuda:<br/>tasa_sobre_maxima"]:::tarea
        D3 -->|sí| D5["Registra la deuda,<br/>sus cargos y su tasa<br/>(un correo mal escrito<br/>entra con un aviso)"]:::tarea
        D5 --> D6["Le manda la invitación<br/>al deudor"]:::tarea
        D6 --> D7((("Deuda en<br/>cobranza"))):::fin
    end

    P3 -. "cartera" .-> A0
    A8 -. "cartera + mandato + campaña" .-> D0
    AR -. "respuesta" .-> P4
    A2 -. "respuesta" .-> P4
    A4 -. "respuesta" .-> P4
    A5 -. "respuesta" .-> P4
```

## Actividad: cómo DataBridge ejecuta una campaña

Cada 15 minutos, DataBridge recorre las campañas en curso y, para cada deuda, decide si le toca un
correo:

```mermaid
flowchart TD
    I(("Cada 15 minutos")) --> H{"¿Es lunes a sábado,<br/>de 8:00 a 20:00,<br/>y no es feriado?"}
    H -->|no| F((("Espera la<br/>próxima pasada")))
    H -->|sí| C{"¿La campaña está en curso<br/>y hoy está entre<br/>su inicio y su fin?"}
    C -->|no| F
    C -->|sí| D{"¿La deuda sigue abierta?<br/>(no está pagada, repactada,<br/>en reclamo ni retirada)"}
    D -->|no| F
    D -->|sí| N{"¿Le quedan intentos?"}
    N -->|no| F
    N -->|sí| T{"¿Ya llegó el día del<br/>próximo correo según<br/>la cadencia?"}
    T -->|no| F
    T -->|sí| L{"¿La ley lo deja?<br/>menos de 2 correos en 7 días<br/>y 2 días desde el último"}
    L -->|no| F
    L -->|sí| E["Le manda el correo con su código<br/>(sin monto ni enlace)"]
    E --> R["Lo anota en la historia de la deuda:<br/>campaña y número de correo"]
    R --> F
```

Un correo que esperó no se pierde: en la pasada siguiente se vuelve a preguntar, y sale en cuanto
la ley lo deja.

## Lo que corre solo

Además de responder peticiones, cada servicio tiene tareas programadas. Ninguna depende de que
haya alguien mirando, y las que avisan a otro sistema escriben primero en una bandeja de salida,
así que un sistema caído no pierde nada:

```mermaid
flowchart LR
    subgraph DEBT["ms-debt"]
        C1["Campañas<br/>cada 15 min"]
        C2["Recordatorio de cuotas<br/>9:00"]
        C3["Avance de campañas<br/>8:00"]
        C4["Bandeja de eventos<br/>cada 15 s"]
    end
    subgraph PAY["ms-payments"]
        P1["Cobros abiertos<br/>cada 10 s"]
        P2["Cobros vencidos<br/>cada 5 min"]
        P3["Aviso de pago<br/>cada 15 s"]
        P4["UF del día<br/>9:30"]
    end
    AUTH["ms-auth"] --> MAIL["Correo del deudor"]
    C1 -->|"código"| AUTH
    C2 -->|"recordatorio"| AUTH
    C3 -->|"campana.avance"| C4
    C4 -->|"eventos firmados"| APX["APOFYX"]
    P1 <-->|"¿en qué va?"| GW["Khipu y<br/>Mercado Pago"]
    P2 <--> GW
    P3 -->|"pago.confirmado"| MQ{{"RabbitMQ"}}
    MQ --> DEBT
    P4 --> BC["Banco Central"]
```

| Tarea | Servicio | Cuándo | Qué hace |
| --- | --- | --- | --- |
| Campañas | ms-debt | Cada 15 minutos | Manda los correos que tocan según la cadencia, dentro del horario y el límite de la ley |
| Recordatorio de cuotas | ms-debt | Todos los días, 9:00 | Avisa las cuotas que vencen en 3 días (`RECORDATORIO_DIAS_ANTES`) |
| Avance de campañas | ms-debt | Todos los días, 8:00 | Publica `campana.avance`: cuántos correos salieron, cuántos entraron al portal, repactaron, pagaron o reclamaron, y cuánto se recuperó |
| Bandeja de eventos | ms-debt | Cada 15 segundos | Entrega los eventos pendientes a cada suscriptor, firmados, con reintentos |
| Cobros abiertos | ms-payments | Cada 10 segundos | Pregunta a Khipu y a Mercado Pago en qué van los cobros abiertos |
| Cobros vencidos | ms-payments | Cada 5 minutos | Vence lo abandonado (también en Webpay) y revisa por 24 horas lo que venció |
| Aviso de pago | ms-payments | Cada 15 segundos | Lleva `pago.confirmado` a ms-debt por RabbitMQ, o por HTTP si RabbitMQ no está |
| UF del día | ms-payments | Todos los días, 9:30 | Trae la UF del Banco Central, para fijar los pesos de las deudas en UF |
