## 8. Reglas del negocio

Lo que DataBridge exige a una cartera, cómo cobra los intereses que pactó el acreedor, y cómo cumple una
campaña dentro de lo que permite la ley.

### Las cifras de cartera

- **Capital recuperado** es el capital original menos el saldo pendiente. La tasa divide esa
  diferencia por el capital original. En un convenio vigente, el saldo pendiente suma las cuotas
  pendientes, que pueden incluir intereses.
- **Ingresado por día** suma los pagos aplicados (`payment_applied`) de los últimos 30 días en la
  moneda de cada pago. Los montos pueden incluir intereses de mora o de convenio.

### El modelo de negocio

DataBridge calcula una comisión mensual por empresa usuaria sobre el total ingresado por pagos.
La paga quien cargó la deuda: la agencia de cobranza o el acreedor que cobra directamente. El deudor
no paga esta comisión y un mismo pago no se atribuye a dos empresas.

`G` es la suma en pesos de los pagos abonados durante el mes calendario, con la hora de Chile,
en todas las campañas de esa empresa. En UF cuentan los pesos cobrados por la pasarela, no una
conversión con la UF de hoy. Los pagos fallidos, anulados o duplicados no se abonan y no suman.

Al aplicar un pago se guardan la empresa que cargó la deuda y su campaña en el detalle del evento.
Recargar después esa deuda con otra empresa o campaña no reasigna ese pago. Para eventos antiguos
sin esa atribución se usan la empresa y campaña actuales: no se puede reconstruir con certeza un
historial que no se guardó. El detalle real usa `monto_clp` y `valor_uf`; también se admiten los nombres
anteriores en camelCase.

| Ingresado por pagos en el mes (G) | Porcentaje p(G) |
| --- | --- |
| Más de $0 y hasta $1.000.000 | 6 % |
| Más de $1.000.000 y hasta $5.000.000 | 10 % |
| Más de $5.000.000 y hasta $10.000.000 | 12 % |
| Más de $10.000.000 | 15 % |

**Comisión de DataBridge:** `P(G) = G × p(G) / 100`, con `p(G)` expresado como 6, 10, 12 o 15.
**Para la empresa:** `G − P(G)`. La comisión se redondea al peso más cercano; medio peso sube.
Sin pagos aplicados, ambos montos y el porcentaje son cero y no hay tramo vigente.

| Ingresado por pagos | Porcentaje | Comisión | Para la empresa |
| --- | --- | --- | --- |
| $800.000 | 6 % | $48.000 | $752.000 |
| $1.000.001 | 10 % | $100.000 | $900.001 |
| $12.000.000 | 15 % | $1.800.000 | $10.200.000 |

**El porcentaje va sobre todo G, no por tramos escalonados.** Al pasar un corte aumenta la comisión
sobre el total: con $1.000.000 quedan $940.000 para la empresa y con $1.000.001 quedan $900.001.
Es el modelo acordado por el equipo y se conserva ese salto.

La empresa ve el mes en *Campañas*, puede consultar uno anterior y ve lo ingresado por pagos en cada campaña.
El admin ve la comisión por empresa en *Empresas*. El tramo depende del total de la empresa,
no de cada campaña por separado. Esto calcula y muestra la comisión: no emite una factura ni
descuenta dinero automáticamente de los pagos de las pasarelas.

| Consulta | Quién la usa | Respuesta |
| --- | --- | --- |
| `GET /api/analytics/comision?mes=2026-10` | Empresa autenticada | Sus pagos aplicados, comisión, tramos y campañas |
| `GET /api/admin/debt/comisiones?mes=2026-10` | Admin de DataBridge | Las empresas con pagos en el mes y los totales |

Sin `mes` se consulta el actual en Chile. En el JSON el mes es una fecha (su primer día), los
montos son números enteros en pesos y los porcentajes son números. El detalle del cálculo está en
[las clases del modelo de negocio](../diagramas/clases.md#el-modelo-de-negocio).

### Las reglas de la cartera

**El alcance son los deudores morosos.** Una deuda entra a cobranza cuando su cargo impago más
antiguo lleva al menos **30 días vencido** (`MIN_DIAS_MORA`). Con menos todavía no es mora, y se
rechaza al recibir la cartera con el código `bajo_umbral_mora`. Se mide en días, y no en meses
impagos, para que sirva en cualquier rubro: un arriendo con dos meses atrasados y un tratamiento
dental de un solo cargo vencido hace 75 días son igual de morosos.

**Una empresa suspendida congela lo suyo** ([#152](https://github.com/TechnicalBridge/DataBridge/issues/152)). El admin de DataBridge la suspende desde su
ficha en el panel, y se corta todo sin borrar nada:
- su gente no entra, no recibe códigos y pierde la sesión al renovarla;
- sus claves de API dejan de servir;
- las deudas que cobra (como acreedor, o como agencia de la campaña o del mandato) quedan en pausa:
  no se les escribe a sus deudores (ni invitación, ni toques de campaña, ni recordatorios, ni el
  código que reenvía otra empresa), y no se pueden pagar ni repactar. El portal del deudor dice que la cobranza está en pausa, y sí se puede
  reclamar;
- no se le carga cartera nueva, ni la carga ella ni una agencia por su cuenta: la revisión y la carga
  responden `409` (`acreedor_suspendido`). Tampoco se declaran ni se renuevan mandatos con ella, aunque
  terminarlos sí se puede. El portal la marca en *Cargar cartera* y en *Mandatos*
  ([#296](https://github.com/TechnicalBridge/DataBridge/issues/296), decidido en
  [#293](https://github.com/TechnicalBridge/DataBridge/issues/293)).

Un cobro que ya estaba abierto en una pasarela se registra igual si el deudor ya pagó: esa plata ya
se movió. Al reactivarla, todo sigue donde quedó. Si al renovar la sesión ms-debt no responde, la
sesión se renueva igual, y la suspensión se nota apenas vuelve.

**Llegan todos los clientes, deban o no.** El acreedor entrega cada mes a todos sus clientes con
contrato, y DataBridge detecta al moroso. El que está al día viene con `cargos: []`:

| La deuda | Qué pasa |
| --- | --- |
| Es nueva | Resultado `al_dia`: no se guarda nada |
| Estaba en cobranza | Se cierra como retirada, motivo `pago_directo`: pagó directo al acreedor |
| Ya estaba pagada o retirada | Nada |

**El mes siguiente no deshace lo acordado.**

- Un convenio sigue en pie: el mes nuevo se agrega como una cuota aparte, que el portal marca
  *Fuera del convenio*.
- Una deuda pagada se reabre si el deudor se vuelve a atrasar, con el mismo mínimo de 30 días.
- La que pasa los 120 días de mora, APOFYX la devuelve al acreedor y DataBridge deja de cobrarla
  (`fuera_de_mandato`).

**La invitación sale sola.** Cuando una deuda entra o se reabre, DataBridge le manda al deudor su
código por correo, sin monto ni enlace. Si el envío falla, la cartera entra igual, y desde el
portal se reenvía.

El detalle está en las [reglas del contrato](../integracion/README.md#64-reglas-de-validación).

### Los intereses

**Solo generan interés las deudas en que el acreedor lo pactó.** El acreedor manda la tasa en cada
deuda (`tasa_interes_mensual`, en porcentaje mensual), por ejemplo porque su contrato de arriendo
comercial la incluye. Ni la agencia ni DataBridge ponen una tasa: cobran la que el acreedor pactó.
Sin tasa, la deuda vale lo que mandó el acreedor y el convenio es sin interés, como siempre.

**Hay un tope legal.** Ningún interés puede superar la tasa máxima convencional, que publica la CMF
cada mes (`INTERES_TASA_MAXIMA_MENSUAL`, hoy 3% mensual). Una deuda con una tasa mayor se rechaza al
entrar, con `tasa_sobre_maxima`.

**La mora: cada cargo atrasado crece todos los días**, desde el día siguiente a su vencimiento:

> interés de un día = monto atrasado × tasa mensual ÷ 30

Es interés simple: el interés no genera más interés, porque la ley (Ley 18.010) no lo permite.

**El pago fija el interés y lo devuelve aparte.** El monto se fija al abrir el cobro: si la pasarela
confirma días después, no se cobra la diferencia. El aviso del pago (`pago.confirmado`) dice cuánto
fue capital y cuánto interés, y el acreedor abona el capital a sus cargos y anota el interés por
separado: Patrimonio deja los tres arriendos pagados y *$18.800 de intereses cobrados*. El recorrido
completo está en la [secuencia del pago](../../README.md#diagrama-de-secuencia-del-flujo-principal).

**El convenio: se repacta lo que debe más la mora de ese día**, en cuotas iguales (sistema francés):
cada cuota paga el interés del mes sobre lo que falta y el resto baja la deuda. Por eso al principio
se paga más interés y al final menos. Antes de aceptar, el portal muestra la tasa, la
cuota, el costo total (la suma de las cuotas) y la **CAE**, la Carga Anual Equivalente que exigen la
Ley 20.555 y el D.S. 43 de 2012 ([#46](https://github.com/TechnicalBridge/TB_web/issues/46)): la tasa
mensual que iguala lo repactado con el valor presente de las cuotas, por 12 y con dos decimales. Sin
tasa, la CAE es 0,00%. La CAE queda guardada con el convenio, así que lo informado al aceptar no
cambia, y viaja en el aviso `repactacion.aceptada`. Si una cuota del convenio se atrasa, genera mora
solo sobre la parte que baja la deuda, no sobre su interés.

<details>
<summary><b>Los ejemplos, probados en vivo</b>: la mora de tres arriendos y un convenio en 6 cuotas</summary>

**La mora.** Tres arriendos de $300.000 al 2% mensual, pagados el 6 de octubre. Cada
uno crece $300.000 × 2% ÷ 30 = **$200 por día**:

| Arriendo | Venció el | Días de atraso | Interés |
| --- | --- | --- | --- |
| Agosto | 5 de agosto | 62 | $12.400 |
| Septiembre | 5 de septiembre | 31 | $6.200 |
| Octubre | 5 de octubre | 1 | $200 |
| **Total** | | | **$18.800** |

El deudor ve **$918.800**, con la línea *Incluye $18.800 de intereses por mora (2% mensual)*, y al
pagar, el desglose: *$900.000 de capital y $18.800 de intereses por mora*.

**El convenio.** Otro contrato, al 1,5% mensual, con tres arriendos de $300.000
y $14.100 de mora (94 días × $150), repactó **$914.100** en 6 cuotas:

| Cuota | Deuda al empezar el mes | Interés del mes | Baja la deuda en | Cuota |
| --- | --- | --- | --- | --- |
| 1 | $914.100 | $13.712 | $146.736 | $160.448 |
| 2 | $767.364 | $11.510 | $148.938 | $160.448 |
| 3 | $618.426 | $9.276 | $151.172 | $160.448 |
| 4 | $467.254 | $7.009 | $153.439 | $160.448 |
| 5 | $313.815 | $4.707 | $155.741 | $160.448 |
| 6 | $158.074 | $2.371 | $158.074 | $160.445 |
| **Total** | | **$48.585** | **$914.100** | **$962.685** |

La última cuota absorbe el redondeo de los pesos.

</details>

| Otros casos | Qué pasa con el interés |
| --- | --- |
| La deuda no trae tasa | Nada cambia: se cobra lo que mandó el acreedor, y el convenio es sin interés |
| El deudor reclama | La mora **sigue corriendo**. Si la empresa le da la razón, la deuda se retira y no se cobra nada; si no, se cobra con los días que pasaron |
| La deuda está en UF | Igual, con dos decimales |

### Cómo crear una campaña con descuento por pronto pago

El descuento beneficia al deudor y se aplica sobre los intereses de mora; es independiente de la
comisión que la empresa paga a DataBridge. Para configurarlo desde el portal:

1. Entra como usuario de la empresa y abre *Campañas*.
2. En *Nueva campaña*, escribe el nombre y elige el acreedor si tienes más de uno.
3. Define inicio, fin, número de contactos y días de contacto. El canal actual es correo.
4. En los descuentos por mora, indica un porcentaje de 0 a 100 para cada tramo: *1 a 30 días*,
   *31 a 90 días* y *Más de 90 días*. Por ejemplo, 0, 50 y 100. Vacío equivale a no ofrecer descuento.
5. Guarda. Si cobras como agencia, ningún porcentaje puede superar el máximo que el acreedor
   autorizó en su mandato; si cobras tu propia cartera, el máximo es 100 %.
6. Usa *Cargar deudores* en esa campaña, sube la planilla y revisa lo que entraría antes de confirmar.
7. El deudor ve la oferta vigente al consultar su deuda. Solo se ofrece en una campaña en curso,
   dentro de sus fechas, al pagar la deuda completa: no al pagar una parte ni una cuota de convenio.

Por ejemplo, sobre $900.000 de capital y $18.800 de mora, un descuento del 50 % de esa mora
rebaja $9.400: el deudor paga $909.400. Lo que se descuenta no es el 50 % del capital. Si la empresa
recuperó solo ese pago en el mes, la comisión de DataBridge es el 6 % de $909.400: $54.564.

### Las campañas

**Quien cobra decide la estrategia y DataBridge la cumple.** Una campaña es el plan para
recordarles a los deudores de una cartera que deben: por qué medio, cuántas veces y cada cuántos
días. La crea quien cobra, en el portal de empresas, en *Campañas*: una empresa de cobranza con
mandato sobre el acreedor, o **el mismo acreedor cuando cobra sin agencia**, sin mandato. Ahí
mismo la cambia, la pausa y la termina, y en ella carga a sus deudores. Con la entrega por API
encendida, un sistema también la registra por `/api/v1/campanas` (en la demo de la cadena, APOFYX
desde su panel), con las mismas reglas. DataBridge manda cada recordatorio, que es el mismo correo
de la invitación: un código para entrar, sin el monto ni un enlace. Si alguien reenvía o roba el correo, no le sirve de
nada, y no hay un enlace que se pueda imitar.

**Una agencia cobra con el mandato de cada acreedor** ([#234](https://github.com/TechnicalBridge/DataBridge/issues/234)). Lo declara ella misma en el portal,
en la sección *Mandatos* de *Campañas*, y responde por lo que declara:
- el acreedor (si DataBridge no lo conoce, lo registra con su razón social), desde y hasta cuándo,
  la mora máxima (pasada esa, el caso vuelve al acreedor) y el descuento máximo que le autoriza;
- queda qué persona lo declaró, lo renovó o lo terminó, y el admin lo ve en la ficha de la agencia;
- tiene un solo mandato a la vez con cada acreedor: el que tiene se renueva o se termina;
- con un acreedor suspendido no se declara ni se renueva un mandato, aunque terminarlo sí se puede,
  y la sección lo marca como *Acreedor suspendido*
  ([#296](https://github.com/TechnicalBridge/DataBridge/issues/296)).

Solo con un mandato vigente se crean campañas nuevas y se carga la cartera de ese acreedor.
Terminado o vencido, **lo ya cargado se sigue cobrando hasta pagarse**, y sus campañas se pueden
pausar o terminar. Una empresa que cobra lo suyo no ve nada de mandatos.

| Dato de la campaña | Qué hace DataBridge |
| --- | --- |
| **Cadencia** (por ejemplo, días 1, 4, 11, 25, 45) | El recordatorio *n* sale ese día, contado desde que la deuda entró a la campaña |
| **Intentos** | Cuántos recordatorios recibe cada deudor como máximo |
| **Inicio y fin** | Fuera de esas fechas no se contacta |
| **Estado** | Solo una campaña en curso contacta. Si quien cobra la pausa o la termina, en el portal (o por la API, si está encendida), DataBridge deja de contactar al instante |
| **Canales** | Hoy solo correo. WhatsApp y SMS se suman cuando estén conectados |

Una deuda que se paga, se repacta, se reclama o se retira deja de recibir recordatorios de la
campaña. La repactada sigue con el recordatorio de cada cuota.

**La ley manda sobre la cadencia** (Ley 19.496, art. 37, con los cambios de la Ley 21.320):

| Regla | Qué exige |
| --- | --- |
| Días | De lunes a sábado. Nunca un domingo ni un feriado: los nacionales se calculan para cualquier año con las reglas de la ley, y `CONTACTO_FERIADOS` suma los que se decretan |
| Horario | De 8:00 a 20:00 |
| Cantidad | Como máximo **dos correos por semana** a una misma persona: los últimos siete días, hoy incluido |
| Separación | Al menos **dos días** entre un correo y el siguiente |
| Registro | Cada correo queda en la historia de la deuda |

Las reglas valen para todos los correos: la invitación, los de la campaña, el recordatorio de una
cuota y el código que reenvía la empresa. Un correo que cae en un momento prohibido no se pierde:
sale en la primera oportunidad permitida.

**Para una demo se pueden apagar** las dos, solo para probar: `CONTACTO_SIN_HORARIO=true` deja escribir a
cualquier hora y `CONTACTO_SIN_LIMITE=true` sin el tope de dos por semana. *Reglas* lo muestra a gritos, y
en producción van en `false` ([§10](../despliegue/docker.md#variables-de-entorno)).

- **La invitación** de una cartera que llega de noche, un domingo o un feriado sale con la primera
  pasada de contacto dentro del horario.
- **El recordatorio de cuota** no sale un domingo ni un feriado. Lo manda la pasada del día
  siguiente en que se puede, que avisa las cuotas que vencen en los próximos tres días.
- **El código que reenvía la empresa** fuera de horario no sale, y la empresa ve el aviso *La ley
  solo deja escribirle a un deudor de lunes a sábado, de 8:00 a 20:00*. Si ya se le escribió lo
  que permite la semana, ve *Ya se le escribió lo que permite la ley*. Cómo decide DataBridge si escribe
  está en el [diagrama de actividad](../diagramas/procesos.md#actividad-cómo-databridge-ejecuta-una-campaña).

<details>
<summary><b>Un ejemplo, día por día</b>: una campaña de cadencia 1, 4, 11 frente a la ley</summary>

**Un ejemplo, día por día**, probado con una campaña de cadencia 1, 4, 11 y 3 intentos, para una
deuda que entró el martes 6 de octubre:

| Día | Qué pasó | Por qué |
| --- | --- | --- |
| Martes 6 | **Sale la invitación** | La deuda entró |
| Miércoles 7 | Nada | Tocaba el correo 1 (día 1), pero la invitación salió ayer: no hay dos días entre uno y otro |
| Jueves 8 | **Sale el correo 1** | Ya pasaron dos días |
| Sábado 10 | Nada | Tocaba el correo 2 (día 4), pero ya van dos correos en la semana |
| Domingo 11 | Nada | Domingo |
| Lunes 12 | Nada | Feriado |
| Martes 13, 7:30 | Nada | Antes de las 8:00 |
| Martes 13, 10:00 | **Sale el correo 2** | La invitación del 6 ya quedó fuera de los últimos siete días |
| Martes 20 | Nada | El correo 3 tocaba el sábado 17 (día 11), pero la agencia pausó la campaña en APOFYX |

Tres correos en total, todos dentro de la ley, aunque la cadencia pedía más seguido. Si la agencia
reanuda la campaña, sigue donde quedó, sin repetir los correos que ya salieron.

</details>

DataBridge revisa las campañas cada 15 minutos.

<details>
<summary><b>Para probar una cadencia</b> sin esperar semanas</summary>

Para probar una cadencia sin esperar semanas:

```powershell
docker compose --profile app exec -T ms-debt sh -c 'curl -s -X POST http://127.0.0.1:8083/internal/campanas/contactos?ahora=2026-10-08T10:00:00\&campana=APX-CMP-8 -H X-Internal-Key:$INTERNAL_KEY'
```

La clave interna no se escribe: el contenedor la tiene en su entorno (`$INTERNAL_KEY`), sacada del
`.env`.

`ahora` es la hora de Chile que se simula y `campana` limita la pasada a una sola campaña. Cada
correo queda fechado a esa hora.

</details>

---
