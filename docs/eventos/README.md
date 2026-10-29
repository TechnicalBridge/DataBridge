# El aviso de pago entre servicios

Cuando un pago se concreta, **ms-payments le avisa a ms-debt** para que abone las cuotas. Es el único mensaje
que dos servicios de DataBridge se mandan por una cola. Cada uno tiene su propia clase `PagoConfirmado`: no
comparten código, comparten este contrato.

## Por dónde viaja

| | |
| --- | --- |
| Exchange | `tbridge.pagos` |
| Clave de enrutamiento | `pago.confirmado` |
| Cola de ms-debt | `ms-debt.pagos-confirmados` |

Con RabbitMQ apagado (`EVENTS_RABBIT=false`, como cuando los servicios corren con Maven), el mismo aviso va por
HTTP a `POST /internal/events/pago-confirmado` de ms-debt, con la clave interna. En los dos casos sale de la
bandeja de salida de ms-payments (`debt_notifications`): si ms-debt no responde, el despachador lo vuelve a
intentar. ms-debt abona una sola vez aunque el aviso le llegue dos.

## El mensaje

```json
{
  "tipo": "pago.confirmado",
  "paymentId": 41,
  "debtId": 3,
  "installmentId": 7,
  "debtorRut": "16482337-7",
  "creditorRut": "76418902-7",
  "amount": 309400,
  "currency": "CLP",
  "amountClp": 309400,
  "ufValue": 39485.65,
  "gateway": "khipu",
  "gatewayTxnId": "kh-ejemplo-0001",
  "paidAt": "2026-10-06T15:30:00Z",
  "installmentIds": [7, 8],
  "interest": 9400,
  "discount": 9400
}
```

| Campo | Qué es |
| --- | --- |
| `tipo` | Siempre `pago.confirmado` |
| `paymentId` | El pago, en `tb_payments` |
| `debtId`, `installmentId`, `installmentIds` | La deuda y las cuotas que cubre el pago, tal como las cobró ms-debt (ids de `tb_debt`) |
| `debtorRut`, `creditorRut` | Quién pagó y a quién. El deudor va por RUT: el correo cambia, el RUT no |
| `amount`, `currency` | Lo pagado, en la moneda de la deuda (`CLP` o `UF`): el capital más `interest` |
| `amountClp`, `ufValue` | Los pesos que cobró la pasarela y el valor de la UF con que se convirtió, para que ms-debt no tenga que volver a convertir |
| `gateway`, `gatewayTxnId` | La pasarela (`webpay`, `khipu` o `mercadopago`) y el id del cobro en ella |
| `paidAt` | Cuándo se pagó |
| `interest` | Cuánto de `amount` es interés de mora, ya con el descuento aplicado |
| `discount` | La mora que se condonó por pronto pago. Sin descuento, no viaja |

Un aviso de antes de los intereses no trae `installmentIds`, `interest` ni `discount`, y ms-debt lo entiende
igual. Al leerlo, un mensaje no puede elegir qué clase se crea.

## Si el aviso cambia

El contrato es `pago-confirmado.json`, con dos copias iguales: una en
[`ms-debt`](../../ms-debt/src/test/resources/contratos/pago-confirmado.json) y otra en
[`ms-payments`](../../ms-payments/src/test/resources/contratos/pago-confirmado.json), en
`src/test/resources/contratos`. Cada servicio tiene su prueba, `PagoConfirmadoContratoTest`, que compara su
clase con su copia:

- **ms-payments**, que lo escribe, manda exactamente el ejemplo, por el exchange y la clave que dice el contrato.
- **ms-debt**, que lo lee, escucha en esa cola y entiende cada campo.

Antes de probar cualquiera de los dos, la CI compara las dos copias byte a byte. Un cambio al aviso se hace en
las dos copias y en las dos clases, en el mismo PR.
