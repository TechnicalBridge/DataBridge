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

