# Planillas de ejemplo de la cartera

Planillas de Excel ya llenas, armadas sobre la plantilla de verdad (la que se descarga en *Cargar cartera*, o con
`GET /api/debts/cartera/plantilla`). Sirven para probar la carga sin escribir una desde cero. Las personas son
inventadas y sus correos son `@example.com`, una dirección reservada que no le llega a nadie.

| Archivo | Qué trae | Qué pasa al revisarla |
| --- | --- | --- |
| [`cartera-correcta.xlsx`](cartera-correcta.xlsx) | Cada caso de la planilla: Felipe debe **tres meses** de arriendo en pesos; Comercial Ñandú, dos meses de un local **en UF**, con interés pactado (1,5%) y su propio concepto por mes; Benjamín, dos aranceles; y Josefina está **al día** | Entran las tres deudas, por **$1.420.000 y 25 UF**, y Josefina queda al día, sin guardarse |
| [`cartera-con-errores.xlsx`](cartera-con-errores.xlsx) | Errores a propósito: un deudor repetido, un tipo que no existe, uno sin correo ni teléfono, una deuda de un RUT que no está en *Deudores*, un mes, un monto y una fecha mal escritos, una deuda que cambia de moneda entre meses, un *al día* con mes y monto, y un retiro sin motivo | No entra nada: responde **11 problemas**, cada uno con su hoja, su fila y su columna, y el portal los muestra en una lista |

Los meses son de agosto a octubre de 2026: para que una deuda entre, su mes impago más antiguo tiene que llevar al
menos 30 días vencido (`MIN_DIAS_MORA`). Si se usan mucho después, conviene correr los meses, porque una agencia
cobra hasta cierto número de días de mora.

## Cómo se usan

1. En el portal de empresas, **Cargar cartera**: se elige o se crea una campaña.
2. **Elegir archivo**, con uno de estos, y **Revisar la planilla**. La revisión no guarda nada ni le escribe a
   nadie.
3. Con la correcta, **Cargar** deja las deudas en la campaña, sin enviar códigos ni agendar invitaciones.

## La demo incluida en DataBridge

Con `DEMO_DATOS=true` y una base vacía, la demo se instala sola: Servicios Horizonte cobra sus propias deudas,
con *Cartera en pesos* y *Cartera en UF*. No usa estas planillas ni las cuentas del equipo: registra dos lotes
por la misma ingesta que usa el portal, con seis deudores ficticios y correos `example.com`. No necesita APOFYX,
Patrimonio ni la API de integración. Las bases con datos propios no se convierten ni se completan.

Las planillas de arriba sirven para demostrar una **carga nueva**, con revisión antes de confirmar. La siembra
inicial prepara además las situaciones de convenio, pagada, reclamo y UF para recorrerlas sin cargar nada.

## La planilla de la demo de campañas

Para la demo hay una tercera planilla, con **las cuentas de prueba del equipo** como deudores, para cargarla en
una campaña y que les lleguen los códigos de verdad. **No está en el repositorio**, porque es público y lleva
correos y RUT del equipo. Está en la carpeta del Capstone (`Docs/cartera-demo-campanas.xlsx`), armada con las
cuentas de `Credenciales.md`: cuatro deudores, siete meses de deuda (septiembre y octubre de 2026), uno en UF y dos
con interés.

Al cargarla no se envían códigos. Cada deudor solicita el suyo en el portal o la empresa usa el reenvío
manual, respetando las reglas de contacto. Con correo real, configurar `CORREO_SOLO_A` y el desvío de prueba.
