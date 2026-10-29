# Probar la carga de la cartera

Los deudores entran a DataBridge solo por una campaña, desde *Cargar cartera* en el portal de empresas, con una
planilla de Excel (una hoja de *Deudores* y otra de *Deudas*, una fila por mes) o con el CSV del contrato. La
plantilla de Excel se descarga ahí mismo, o con `GET /api/debts/cartera/plantilla`.

## Cómo se prueba

1. En el portal de empresas, **Cargar cartera**: se elige o se crea una campaña.
2. **Descargar la plantilla** y llenarla con personas inventadas y correos `@example.com`, una dirección
   reservada que no le llega a nadie.
3. **Elegir archivo** y **Revisar la planilla**. La revisión no guarda nada ni le escribe a nadie: dice qué
   entraría y qué no, con su motivo, y si la planilla tiene problemas, cada uno con su hoja, su fila y su columna.
4. Con una planilla correcta, **Cargar** deja las deudas en la campaña, y a cada deudor nuevo le sale su
   invitación.

Para que una deuda entre, su mes impago más antiguo tiene que llevar al menos 30 días vencido (`MIN_DIAS_MORA`).

**Lo que conviene probar:**

- **Que entre:** una deuda en pesos de varios meses; una **en UF**, con interés pactado y su propio concepto por
  mes; y un deudor **al día**, que se revisa pero no se guarda.
- **Que se rechace, con su hoja, fila y columna:**
  - un deudor repetido, un tipo que no existe, o uno sin correo ni teléfono;
  - una deuda de un RUT que no está en *Deudores*;
  - un mes, un monto o una fecha mal escritos;
  - una deuda que cambia de moneda entre meses;
  - un *al día* con mes y monto, o un retiro sin motivo.

  Con un error de forma en la planilla no entra nada. Lo de negocio (el dígito del RUT, la mora mínima, la
  tasa) se decide deuda por deuda, y la revisión dice cuáles entrarían y cuáles no.

## La demo incluida en DataBridge

Con `DEMO_DATOS=true` y una base vacía, la demo se instala sola: Servicios Horizonte cobra sus propias deudas,
con *Cartera en pesos* y *Cartera en UF*. No usa planillas ni las cuentas del equipo: registra dos lotes
por la misma ingesta que usa el portal, con seis deudores ficticios y correos `example.com`. No necesita APOFYX,
Patrimonio ni la API de integración. Las bases con datos propios no se convierten ni se completan.

Una planilla propia sirve para demostrar una **carga nueva**, con revisión antes de confirmar. La siembra
inicial prepara además las situaciones de convenio, pagada, reclamo y UF para recorrerlas sin cargar nada.

## La planilla de la demo de campañas

Para la demo hay una tercera planilla, con **las cuentas de prueba del equipo** como deudores, para cargarla en
una campaña y que les lleguen los códigos de verdad. **No está en el repositorio**, porque es público y lleva
correos y RUT del equipo. Está en la carpeta del Capstone (`Docs/cartera-demo-campanas.xlsx`), armada con las
cuentas de `Credenciales.md`: cuatro deudores, siete meses de deuda (septiembre y octubre de 2026), uno en UF y dos
con interés.

Al cargarla, las invitaciones salen solas, dentro del horario de la ley salvo que esté apagado para probar
(`CONTACTO_SIN_HORARIO`, `CONTACTO_SIN_LIMITE`). Con el correo de verdad, las cuentas tienen que estar en
`CORREO_SOLO_A`.
