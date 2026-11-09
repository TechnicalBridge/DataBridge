## 8. Reglas del negocio

Lo que DataBridge exige a una cartera, c├│mo cobra los intereses que pact├│ el acreedor, y c├│mo cumple una
campa├▒a dentro de lo que permite la ley.

### El modelo de negocio

DataBridge calcula una comisi├│n mensual por empresa usuaria sobre lo que recupera con la plataforma.
La paga quien carg├│ la deuda: la agencia de cobranza o el acreedor que cobra directamente. El deudor
no paga esta comisi├│n y un mismo pago no se atribuye a dos empresas.

`G` es la suma en pesos de los pagos abonados durante el mes calendario, con la hora de Chile,
en todas las campa├▒as de esa empresa. En UF cuentan los pesos cobrados por la pasarela, no una
conversi├│n con la UF de hoy. Los pagos fallidos, anulados o duplicados no se abonan y no suman.

Al aplicar un pago se guardan la empresa que carg├│ la deuda y su campa├▒a en el detalle del evento.
Recargar despu├⌐s esa deuda con otra empresa o campa├▒a no reasigna ese pago. Para eventos antiguos
sin esa atribuci├│n se usan la empresa y campa├▒a actuales: no se puede reconstruir con certeza un
historial que no se guard├│. El detalle real usa `monto_clp` y `valor_uf`; tambi├⌐n se admiten los nombres
anteriores en camelCase.

| Recuperado por la empresa en el mes (G) | Porcentaje p(G) |
| --- | --- |
| M├ís de $0 y hasta $1.000.000 | 6 % |
| M├ís de $1.000.000 y hasta $5.000.000 | 10 % |
| M├ís de $5.000.000 y hasta $10.000.000 | 12 % |
| M├ís de $10.000.000 | 15 % |

**Comisi├│n de DataBridge:** `P(G) = G ├ù p(G) / 100`, con `p(G)` expresado como 6, 10, 12 o 15.
**Para la empresa:** `G ΓêÆ P(G)`. La comisi├│n se redondea al peso m├ís cercano; medio peso sube.
Sin recuperaci├│n, ambos montos y el porcentaje son cero y no hay tramo vigente.

| Recuperado | Porcentaje | Comisi├│n | Para la empresa |
| --- | --- | --- | --- |
| $800.000 | 6 % | $48.000 | $752.000 |
| $1.000.001 | 10 % | $100.000 | $900.001 |
| $12.000.000 | 15 % | $1.800.000 | $10.200.000 |

**El porcentaje va sobre todo G, no por tramos escalonados.** Al pasar un corte aumenta la comisi├│n
sobre el total: con $1.000.000 quedan $940.000 para la empresa y con $1.000.001 quedan $900.001.
Es el modelo acordado por el equipo y se conserva ese salto.

La empresa ve el mes en *Campa├▒as*, puede consultar uno anterior y ve lo recuperado por cada campa├▒a.
El admin ve la comisi├│n por empresa en *Empresas*. El tramo depende del total de la empresa,
no de cada campa├▒a por separado. Esto calcula y muestra la comisi├│n: no emite una factura ni
descuenta dinero autom├íticamente de los pagos de las pasarelas.

| Consulta | Qui├⌐n la usa | Respuesta |
| --- | --- | --- |
| `GET /api/analytics/comision?mes=2026-10` | Empresa autenticada | Solo su recuperaci├│n, comisi├│n, tramos y campa├▒as |
| `GET /api/admin/debt/comisiones?mes=2026-10` | Admin de DataBridge | Las empresas con pagos en el mes y los totales |

Sin `mes` se consulta el actual en Chile. En el JSON el mes es una fecha (su primer d├¡a), los
montos son n├║meros enteros en pesos y los porcentajes son n├║meros. El detalle del c├ílculo est├í en
[las clases del modelo de negocio](docs/diagramas/clases.md#el-modelo-de-negocio).

### Las reglas de la cartera

**El alcance son los deudores morosos.** Una deuda entra a cobranza cuando su cargo impago m├ís
antiguo lleva al menos **30 d├¡as vencido** (`MIN_DIAS_MORA`). Con menos todav├¡a no es mora, y se
rechaza al recibir la cartera con el c├│digo `bajo_umbral_mora`. Se mide en d├¡as, y no en meses
impagos, para que sirva en cualquier rubro: un arriendo con dos meses atrasados y un tratamiento
dental de un solo cargo vencido hace 75 d├¡as son igual de morosos.

**Una empresa suspendida congela lo suyo** ([#152](https://github.com/TechnicalBridge/DataBridge/issues/152)). El admin de DataBridge la suspende desde su
ficha en el panel, y se corta todo sin borrar nada:
- su gente no entra, no recibe c├│digos y pierde la sesi├│n al renovarla;
- sus claves de API dejan de servir;
- las deudas que cobra (como acreedor, o como agencia de la campa├▒a o del mandato) quedan en pausa:
  no se les escribe a sus deudores (ni invitaci├│n, ni toques de campa├▒a, ni recordatorios, ni el
  c├│digo que reenv├¡a otra empresa), y no se pueden pagar ni repactar. El portal del deudor dice que la cobranza est├í en pausa, y s├¡ se puede
  reclamar;
- no se le carga cartera nueva, ni la carga ella ni una agencia por su cuenta: la revisi├│n y la carga
  responden `409` (`acreedor_suspendido`). Tampoco se declaran ni se renuevan mandatos con ella, aunque
  terminarlos s├¡ se puede. El portal la marca en *Cargar cartera* y en *Mandatos*
  ([#296](https://github.com/TechnicalBridge/DataBridge/issues/296), decidido en
  [#293](https://github.com/TechnicalBridge/DataBridge/issues/293)).

Un cobro que ya estaba abierto en una pasarela se registra igual si el deudor ya pag├│: esa plata ya
se movi├│. Al reactivarla, todo sigue donde qued├│. Si al renovar la sesi├│n ms-debt no responde, la
sesi├│n se renueva igual, y la suspensi├│n se nota apenas vuelve.

**Llegan todos los clientes, deban o no.** El acreedor entrega cada mes a todos sus clientes con
contrato, y DataBridge detecta al moroso. El que est├í al d├¡a viene con `cargos: []`:

| La deuda | Qu├⌐ pasa |
| --- | --- |
| Es nueva | Resultado `al_dia`: no se guarda nada |
| Estaba en cobranza | Se cierra como retirada, motivo `pago_directo`: pag├│ directo al acreedor |
| Ya estaba pagada o retirada | Nada |

**El mes siguiente no deshace lo acordado.**

- Un convenio sigue en pie: el mes nuevo se agrega como una cuota aparte, que el portal marca
  *Fuera del convenio*.
- Una deuda pagada se reabre si el deudor se vuelve a atrasar, con el mismo m├¡nimo de 30 d├¡as.
- La que pasa los 120 d├¡as de mora, APOFYX la devuelve al acreedor y DataBridge deja de cobrarla
  (`fuera_de_mandato`).

**La invitaci├│n sale sola.** Cuando una deuda entra o se reabre, DataBridge le manda al deudor su
c├│digo por correo, sin monto ni enlace. Si el env├¡o falla, la cartera entra igual, y desde el
portal se reenv├¡a.

El detalle est├í en las [reglas del contrato](docs/integracion/README.md#64-reglas-de-validaci├│n).

### Los intereses

**Solo generan inter├⌐s las deudas en que el acreedor lo pact├│.** El acreedor manda la tasa en cada
deuda (`tasa_interes_mensual`, en porcentaje mensual), por ejemplo porque su contrato de arriendo
comercial la incluye. Ni la agencia ni DataBridge ponen una tasa: cobran la que el acreedor pact├│.
Sin tasa, la deuda vale lo que mand├│ el acreedor y el convenio es sin inter├⌐s, como siempre.

**Hay un tope legal.** Ning├║n inter├⌐s puede superar la tasa m├íxima convencional, que publica la CMF
cada mes (`INTERES_TASA_MAXIMA_MENSUAL`, hoy 3% mensual). Una deuda con una tasa mayor se rechaza al
entrar, con `tasa_sobre_maxima`.

**La mora: cada cargo atrasado crece todos los d├¡as**, desde el d├¡a siguiente a su vencimiento:

> inter├⌐s de un d├¡a = monto atrasado ├ù tasa mensual ├╖ 30

Es inter├⌐s simple: el inter├⌐s no genera m├ís inter├⌐s, porque la ley (Ley 18.010) no lo permite.

**El pago fija el inter├⌐s y lo devuelve aparte.** El monto se fija al abrir el cobro: si la pasarela
confirma d├¡as despu├⌐s, no se cobra la diferencia. El aviso del pago (`pago.confirmado`) dice cu├ínto
fue capital y cu├ínto inter├⌐s, y el acreedor abona el capital a sus cargos y anota el inter├⌐s por
separado: Patrimonio deja los tres arriendos pagados y *$18.800 de intereses cobrados*. El recorrido
completo est├í en la [secuencia del pago](#el-pago-con-khipu-la-funcionalidad-principal).

**El convenio: se repacta lo que debe m├ís la mora de ese d├¡a**, en cuotas iguales (sistema franc├⌐s):
cada cuota paga el inter├⌐s del mes sobre lo que falta y el resto baja la deuda. Por eso al principio
se paga m├ís inter├⌐s y al final menos. Antes de aceptar, el portal muestra la tasa, la
cuota, el costo total (la suma de las cuotas) y la **CAE**, la Carga Anual Equivalente que exigen la
Ley 20.555 y el D.S. 43 de 2012 ([#46](https://github.com/TechnicalBridge/TB_web/issues/46)): la tasa
mensual que iguala lo repactado con el valor presente de las cuotas, por 12 y con dos decimales. Sin
tasa, la CAE es 0,00%. La CAE queda guardada con el convenio, as├¡ que lo informado al aceptar no
cambia, y viaja en el aviso `repactacion.aceptada`. Si una cuota del convenio se atrasa, genera mora
solo sobre la parte que baja la deuda, no sobre su inter├⌐s.

<details>
<summary><b>Los ejemplos, probados en vivo</b>: la mora de tres arriendos y un convenio en 6 cuotas</summary>

**La mora.** Tres arriendos de $300.000 al 2% mensual, pagados el 6 de octubre. Cada
uno crece $300.000 ├ù 2% ├╖ 30 = **$200 por d├¡a**:

| Arriendo | Venci├│ el | D├¡as de atraso | Inter├⌐s |
| --- | --- | --- | --- |
| Agosto | 5 de agosto | 62 | $12.400 |
| Septiembre | 5 de septiembre | 31 | $6.200 |
| Octubre | 5 de octubre | 1 | $200 |
| **Total** | | | **$18.800** |

El deudor ve **$918.800**, con la l├¡nea *Incluye $18.800 de intereses por mora (2% mensual)*, y al
pagar, el desglose: *$900.000 de capital y $18.800 de intereses por mora*.

**El convenio.** Otro contrato, al 1,5% mensual, con tres arriendos de $300.000
y $14.100 de mora (94 d├¡as ├ù $150), repact├│ **$914.100** en 6 cuotas:

| Cuota | Deuda al empezar el mes | Inter├⌐s del mes | Baja la deuda en | Cuota |
| --- | --- | --- | --- | --- |
| 1 | $914.100 | $13.712 | $146.736 | $160.448 |
| 2 | $767.364 | $11.510 | $148.938 | $160.448 |
| 3 | $618.426 | $9.276 | $151.172 | $160.448 |
| 4 | $467.254 | $7.009 | $153.439 | $160.448 |
| 5 | $313.815 | $4.707 | $155.741 | $160.448 |
| 6 | $158.074 | $2.371 | $158.074 | $160.445 |
| **Total** | | **$48.585** | **$914.100** | **$962.685** |

La ├║ltima cuota absorbe el redondeo de los pesos.

</details>

| Otros casos | Qu├⌐ pasa con el inter├⌐s |
| --- | --- |
| La deuda no trae tasa | Nada cambia: se cobra lo que mand├│ el acreedor, y el convenio es sin inter├⌐s |
| El deudor reclama | La mora **sigue corriendo**. Si la empresa le da la raz├│n, la deuda se retira y no se cobra nada; si no, se cobra con los d├¡as que pasaron |
| La deuda est├í en UF | Igual, con dos decimales |

### C├│mo crear una campa├▒a con descuento por pronto pago

El descuento beneficia al deudor y se aplica sobre los intereses de mora; es independiente de la
comisi├│n que la empresa paga a DataBridge. Para configurarlo desde el portal:

1. Entra como usuario de la empresa y abre *Campa├▒as*.
2. En *Nueva campa├▒a*, escribe el nombre y elige el acreedor si tienes m├ís de uno.
3. Define inicio, fin, n├║mero de contactos y d├¡as de contacto. El canal actual es correo.
4. En los descuentos por mora, indica un porcentaje de 0 a 100 para cada tramo: *1 a 30 d├¡as*,
   *31 a 90 d├¡as* y *M├ís de 90 d├¡as*. Por ejemplo, 0, 50 y 100. Vac├¡o equivale a no ofrecer descuento.
5. Guarda. Si cobras como agencia, ning├║n porcentaje puede superar el m├íximo que el acreedor
   autoriz├│ en su mandato; si cobras tu propia cartera, el m├íximo es 100 %.
6. Usa *Cargar deudores* en esa campa├▒a, sube la planilla y revisa lo que entrar├¡a antes de confirmar.
7. El deudor ve la oferta vigente al consultar su deuda. Solo se ofrece en una campa├▒a en curso,
   dentro de sus fechas, al pagar la deuda completa: no al pagar una parte ni una cuota de convenio.

Por ejemplo, sobre $900.000 de capital y $18.800 de mora, un descuento del 50 % de esa mora
rebaja $9.400: el deudor paga $909.400. Lo que se descuenta no es el 50 % del capital. Si la empresa
recuper├│ solo ese pago en el mes, la comisi├│n de DataBridge es el 6 % de $909.400: $54.564.

### Las campa├▒as

**Quien cobra decide la estrategia y DataBridge la cumple.** Una campa├▒a es el plan para
recordarles a los deudores de una cartera que deben: por qu├⌐ medio, cu├íntas veces y cada cu├íntos
d├¡as. La crea quien cobra, en el portal de empresas, en *Campa├▒as*: una empresa de cobranza con
mandato sobre el acreedor, o **el mismo acreedor cuando cobra sin agencia**, sin mandato. Ah├¡
mismo la cambia, la pausa y la termina, y en ella carga a sus deudores. Con la entrega por API
encendida, un sistema tambi├⌐n la registra por `/api/v1/campanas` (en la demo de la cadena, APOFYX
desde su panel), con las mismas reglas. DataBridge manda cada recordatorio, que es el mismo correo
de la invitaci├│n: un c├│digo para entrar, sin el monto ni un enlace. Si alguien reenv├¡a o roba el correo, no le sirve de
nada, y no hay un enlace que se pueda imitar.

**Una agencia cobra con el mandato de cada acreedor** ([#234](https://github.com/TechnicalBridge/DataBridge/issues/234)). Lo declara ella misma en el portal,
en la secci├│n *Mandatos* de *Campa├▒as*, y responde por lo que declara:
- el acreedor (si DataBridge no lo conoce, lo registra con su raz├│n social), desde y hasta cu├índo,
  la mora m├íxima (pasada esa, el caso vuelve al acreedor) y el descuento m├íximo que le autoriza;
- queda qu├⌐ persona lo declar├│, lo renov├│ o lo termin├│, y el admin lo ve en la ficha de la agencia;
- tiene un solo mandato a la vez con cada acreedor: el que tiene se renueva o se termina;
- con un acreedor suspendido no se declara ni se renueva un mandato, aunque terminarlo s├¡ se puede,
  y la secci├│n lo marca como *Acreedor suspendido*
  ([#296](https://github.com/TechnicalBridge/DataBridge/issues/296)).

Solo con un mandato vigente se crean campa├▒as nuevas y se carga la cartera de ese acreedor.
Terminado o vencido, **lo ya cargado se sigue cobrando hasta pagarse**, y sus campa├▒as se pueden
pausar o terminar. Una empresa que cobra lo suyo no ve nada de mandatos.

| Dato de la campa├▒a | Qu├⌐ hace DataBridge |
| --- | --- |
| **Cadencia** (por ejemplo, d├¡as 1, 4, 11, 25, 45) | El recordatorio *n* sale ese d├¡a, contado desde que la deuda entr├│ a la campa├▒a |
| **Intentos** | Cu├íntos recordatorios recibe cada deudor como m├íximo |
| **Inicio y fin** | Fuera de esas fechas no se contacta |
| **Estado** | Solo una campa├▒a en curso contacta. Si quien cobra la pausa o la termina, en el portal (o por la API, si est├í encendida), DataBridge deja de contactar al instante |
| **Canales** | Hoy solo correo. WhatsApp y SMS se suman cuando est├⌐n conectados |

Una deuda que se paga, se repacta, se reclama o se retira deja de recibir recordatorios de la
campa├▒a. La repactada sigue con el recordatorio de cada cuota.

**La ley manda sobre la cadencia** (Ley 19.496, art. 37, con los cambios de la Ley 21.320):

| Regla | Qu├⌐ exige |
| --- | --- |
| D├¡as | De lunes a s├íbado. Nunca un domingo ni un feriado: los nacionales se calculan para cualquier a├▒o con las reglas de la ley, y `CONTACTO_FERIADOS` suma los que se decretan |
| Horario | De 8:00 a 20:00 |
| Cantidad | Como m├íximo **dos correos por semana** a una misma persona: los ├║ltimos siete d├¡as, hoy incluido |
| Separaci├│n | Al menos **dos d├¡as** entre un correo y el siguiente |
| Registro | Cada correo queda en la historia de la deuda |

Las reglas valen para todos los correos: la invitaci├│n, los de la campa├▒a, el recordatorio de una
cuota y el c├│digo que reenv├¡a la empresa. Un correo que cae en un momento prohibido no se pierde:
sale en la primera oportunidad permitida.

**Para una demo se pueden apagar** las dos, solo para probar: `CONTACTO_SIN_HORARIO=true` deja escribir a
cualquier hora y `CONTACTO_SIN_LIMITE=true` sin el tope de dos por semana. *Reglas* lo muestra a gritos, y
en producci├│n van en `false` ([┬º10](#variables-de-entorno)).

- **La invitaci├│n** de una cartera que llega de noche, un domingo o un feriado sale con la primera
  pasada de contacto dentro del horario.
- **El recordatorio de cuota** no sale un domingo ni un feriado. Lo manda la pasada del d├¡a
  siguiente en que se puede, que avisa las cuotas que vencen en los pr├│ximos tres d├¡as.
- **El c├│digo que reenv├¡a la empresa** fuera de horario no sale, y la empresa ve el aviso *La ley
  solo deja escribirle a un deudor de lunes a s├íbado, de 8:00 a 20:00*. Si ya se le escribi├│ lo
  que permite la semana, ve *Ya se le escribi├│ lo que permite la ley*. C├│mo decide DataBridge si escribe
  est├í en el [diagrama de actividad](docs/diagramas/procesos.md#actividad-c├│mo-databridge-ejecuta-una-campa├▒a).

<details>
<summary><b>Un ejemplo, d├¡a por d├¡a</b>: una campa├▒a de cadencia 1, 4, 11 frente a la ley</summary>

**Un ejemplo, d├¡a por d├¡a**, probado con una campa├▒a de cadencia 1, 4, 11 y 3 intentos, para una
deuda que entr├│ el martes 6 de octubre:

| D├¡a | Qu├⌐ pas├│ | Por qu├⌐ |
| --- | --- | --- |
| Martes 6 | **Sale la invitaci├│n** | La deuda entr├│ |
| Mi├⌐rcoles 7 | Nada | Tocaba el correo 1 (d├¡a 1), pero la invitaci├│n sali├│ ayer: no hay dos d├¡as entre uno y otro |
| Jueves 8 | **Sale el correo 1** | Ya pasaron dos d├¡as |
| S├íbado 10 | Nada | Tocaba el correo 2 (d├¡a 4), pero ya van dos correos en la semana |
| Domingo 11 | Nada | Domingo |
| Lunes 12 | Nada | Feriado |
| Martes 13, 7:30 | Nada | Antes de las 8:00 |
| Martes 13, 10:00 | **Sale el correo 2** | La invitaci├│n del 6 ya qued├│ fuera de los ├║ltimos siete d├¡as |
| Martes 20 | Nada | El correo 3 tocaba el s├íbado 17 (d├¡a 11), pero la agencia paus├│ la campa├▒a en APOFYX |

Tres correos en total, todos dentro de la ley, aunque la cadencia ped├¡a m├ís seguido. Si la agencia
reanuda la campa├▒a, sigue donde qued├│, sin repetir los correos que ya salieron.

</details>

DataBridge revisa las campa├▒as cada 15 minutos.

<details>
<summary><b>Para probar una cadencia</b> sin esperar semanas</summary>

Para probar una cadencia sin esperar semanas:

```powershell
docker compose --profile app exec -T ms-debt sh -c 'curl -s -X POST http://127.0.0.1:8083/internal/campanas/contactos?ahora=2026-10-08T10:00:00\&campana=APX-CMP-8 -H X-Internal-Key:$INTERNAL_KEY'
```

La clave interna no se escribe: el contenedor la tiene en su entorno (`$INTERNAL_KEY`), sacada del
`.env`.

`ahora` es la hora de Chile que se simula y `campana` limita la pasada a una sola campa├▒a. Cada
correo queda fechado a esa hora.

</details>

---

