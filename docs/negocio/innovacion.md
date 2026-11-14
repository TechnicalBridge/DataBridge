## 12. Innovación

**¿Qué problema resuelve?** Está en [§1](../../README.md#qué-problema-resuelve): cobrar deudas chicas cuesta más
que la deuda, y el deudor que quiere pagar se topa con un horario de oficina.

**¿Qué hace diferente a la solución?**

1. **Se entra sin cuenta.** El deudor no crea un usuario ni inventa una contraseña: escribe su RUT
   y el código que le llegó. Una cuenta más es una razón más para no pagar, y una base de
   contraseñas más que se puede filtrar.
2. **Una empresa empieza sin integrar nada.** Un `admin` la da de alta, y su gente crea una
   campaña y carga a sus deudores con una planilla, viendo antes qué entraría y qué no. No hace
   falta un sistema propio ni tocar código. La que sí tiene uno puede, con la entrega por API
   encendida, hablarle a DataBridge por un contrato versionado, sin una base compartida: así se
   probó la cadena completa con APOFYX y Patrimonio.
3. **La empresa ve todo lo que pasa.** Lo difícil de la cobranza tercerizada no es cobrar: es que
   el que encargó la cobranza se entere. Cada pago, convenio y reclamo queda a la vista en el
   portal de la empresa; un reclamo frena la cobranza mientras se revisa, para que nadie le siga
   cobrando a quien dice que ya pagó. Con la API encendida, además viaja firmado hasta el sistema
   del acreedor y deja la deuda en $0 allá.

**¿Qué valor agrega?** Al acreedor, cobranza de montos bajos que antes no era rentable, y certeza
de que su cartera está al día. Al deudor, pagar a cualquier hora, ver el detalle de lo que debe,
repactar en cuotas y reclamar sin llamar a nadie. A la empresa que cobra, una cartera
que se carga con una planilla, revisada antes de confirmar, y una campaña que se cumple sola dentro
de lo que permite la ley, sin que nadie lleve la cuenta.

---
