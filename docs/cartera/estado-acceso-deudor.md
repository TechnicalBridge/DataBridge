# Estado de acceso por deudor

La cartera comparte `codigoEnviado` entre las deudas del mismo deudor visibles
para la organización que consulta. Conserva el campo existente y no crea contactos
ficticios: toma el máximo de los contactos ya autorizados en una consulta por lote.

El nuevo campo opcional `accesoConfirmado` usa exclusivamente eventos persistidos
`portal_entered` de esas mismas deudas, agregados por deudor en SQL. Un correo
aceptado no se interpreta como ingreso ni como entrega confirmada. No se consulta
ms-auth por cada deuda. No cambia la autorización de cartera ni el envío manual.

El alcance es deliberadamente la cartera autorizada: no se comparten eventos de
acreedores ajenos aunque pertenezcan a la misma persona. La ficha administrativa
y los contratos de detalle conservan su comportamiento anterior.

## Dependencia de #339

BLOCKED_BY: arquitectura de despacho del PR #402 (#308), todavía abierto.
EVIDENCE: la revisión de ese PR confirmó una invitación inicial con dos deudas,
pero dejó pendiente la segunda pasada y la conciliación de trabajos UNCERTAIN.
SAFE_WORK_COMPLETED: presentación coherente de contactos e ingresos existentes
en la cartera visible; ninguna nueva cola ni modificación de la rama de #402.
NEXT_EXACT_ACTION: integrar o coordinar el contrato de despacho de #402; definir
idempotencia inicial persistente por deudor/organización/destino, reintentos y
conciliación; probar dos/diez deudas, pasadas posteriores y límites en MySQL/Mailpit.

Este trabajo es parcial respecto de #339 y no autoriza cerrarla. La validación
MySQL, Edge en ambos anchos/temas y pruebas integradas de correo siguen pendientes.
