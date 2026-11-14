## 13. Recorrido de demostración

1. **La empresa entra.** Con la demo encendida (`DEMO_DATOS=true`) en una base nueva, en *Soy de
   una empresa*, `camila.reyes@example.com` pide su código, que llega al buzón, y ve las dos
   campañas de Servicios Horizonte.
2. **Carga a sus deudores en una campaña.** En *Cargar cartera* elige una campaña o la crea, descarga
   la plantilla de Excel, la llena (una hoja de deudores y otra de deudas, una fila por mes) y la sube.
   Ve qué entraría y qué no, con su motivo; si la planilla tiene problemas, cada uno con su hoja, fila
   y columna. Recién al confirmar entran. Cómo probarla está en
   [`docs/cartera`](../cartera/README.md).
3. **El deudor recibe su código solo.** Al entrar la deuda, la invitación sale a su correo, y la
   cartera muestra *Invitado el …*. La pantalla no muestra el código: quien lo viera podría entrar
   en su lugar.
4. **El deudor entra** con su RUT y ese código. Puede simular un plan, aceptarlo y pagar una o
   varias cuotas, en orden.
5. **O reclama.** *No reconozco esta deuda*, con un motivo y, si quiere, un detalle. La deuda queda
   **En revisión**. La empresa la ve con el filtro del mismo nombre, y toca **Reanudar cobro** o
   **Retirar**.
6. **Paga con Webpay**, con la [tarjeta de prueba](../../README.md#cuentas-de-prueba-y-demostración), en la página real de
   Transbank; o con Khipu, con el banco de prueba ([cómo activarlo](../../README.md#cuentas-de-prueba-y-demostración)).
7. **El pago llega a la empresa.** ms-payments avisa a ms-debt, que abona la deuda. Con la
   entrega por API encendida y suscripciones configuradas, los sistemas conectados pueden recibir
   sus eventos. La demo independiente no necesita esa integración.
8. **Queda el rastro.** El deudor ve el pago en su historial, con qué cuotas cubrió, y descarga el
   comprobante. La empresa lo ve en *Pagos recibidos*.

**Las marcas de las pasarelas.** Webpay, Mercado Pago y Khipu aparecen con su logo oficial, tal
como Transbank, Mercado Pago y Khipu los publican para los comercios, porque es lo que el deudor
reconoce. Los logos son marcas de sus dueños. Las simuladas lo dicen en pantalla: *Simulación*.

**Pagos en UF.** Usan la UF de ese día exacto, del **Banco Central**. Como la publica con un mes
de adelanto, ms-payments la carga al arrancar y cada mañana a las 9:30. Sin credenciales, se carga
a mano:

```powershell
$cuerpo = @{ dia = "2026-10-03"; valor = "39876.54" } | ConvertTo-Json
$cuerpo | docker compose --profile app exec -T ms-payments sh -c 'curl -s -X POST http://127.0.0.1:8084/internal/uf -H X-Internal-Key:$INTERNAL_KEY -H Content-Type:application/json --data-binary @-'
```

---
