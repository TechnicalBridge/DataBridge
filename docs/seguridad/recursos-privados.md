# Recursos privados por ID (#321, PR #404)

Las rutas privadas devuelven el mismo estado y mensaje para un recurso ajeno y uno
inexistente. La sesión y el rol se comprueban según el contrato de cada ruta.

| Rutas autenticadas | Recurso ajeno o inexistente |
| --- | --- |
| `GET /api/debts/{id}`, `GET /api/debts/{id}/simulate` | `403`, `No puedes ver esta deuda` |
| `POST /api/debts/{id}/repact`, `POST /api/debts/{id}/disputa` | `403`, `No puedes ver esta deuda` |
| `POST /api/debts/{id}/disputa/resolucion`, `POST /api/debts/{id}/codigo` | `403`, `No puedes ver esta deuda` |
| `GET /api/debts/{id}/certificate`, `GET /api/debts/pagos/{id}/comprobante` | `403`, `No puedes ver esta deuda` |
| `POST /api/payments/checkout` (`debtId` o cuota seleccionada ajena/inexistente) | `403`, `No puedes ver esta deuda` |
| `GET /api/payments/{id}`, `GET /api/payments/{id}/historia` | `403`, `No puedes ver este pago` |
| `POST /api/payments/{id}/cancelar`, `POST /api/payments/{id}/devolucion` | `403`, `No puedes ver este pago` |

## Autorización del checkout

`ms-payments` toma el RUT del JWT verificado y consulta
`GET /internal/debts/{id}/checkout?debtorRut=...&installmentIds=...`, con
`X-Internal-Key`. El RUT no se acepta desde el cuerpo público. La ruta interna
exige ese parámetro y la clave; el gateway no publica `/internal`.

`ms-debt` busca la deuda y comprueba su propietario antes de revisar disputa,
suspensión, saldo, cuotas, mora o descuentos. Una deuda ajena, aunque esté en
conflicto, responde igual que una inexistente. Para una deuda propia, una cuota
inexistente y una de otra deuda reciben también el mismo `403` y mensaje.
El rechazo no guarda pagos ni eventos ni llama a una pasarela.

Los conflictos legítimos conservan su `409` y texto: deuda en disputa, empresa
suspendida, deuda sin saldo, cuota propia que ya no está pendiente, cuotas fuera
de orden y pagos ya cubiertos o en verificación. Las cuotas repetidas conservan
su `400`. Los fallos de disponibilidad conservan `503`; no se convierten en
rechazos de permisos. La validación de formato del cuerpo conserva `400`.

El snapshot interno anterior (`GET /internal/debts/{id}`) conserva su contrato
para otros consumidores. Para desplegar este cambio, actualizar `ms-debt` antes
de `ms-payments` o ambos juntos: el checkout necesita la nueva ruta. Para revertir,
restaurar primero `ms-payments` y después `ms-debt`; no hay migraciones nuevas.

## Alcance y regresión

Las claves, mandatos y campañas filtran por empresa y mantienen su `404` neutro.
Los contratos de integración v1, admin, firmas, callbacks, webhooks y pagos públicos
conservan sus reglas. Cancelar mantiene el bloqueo de fila.

`DebtServiceTest` comprueba el rechazo antes de consultar cuotas, el resultado
idéntico para recursos ajenos/inexistentes, los montos propios y los conflictos.
`InternalControllerTest` cubre la clave y el envío de identidad y cuotas.
`DebtClientTest` usa HTTP local para verificar la ruta, los parámetros, el rechazo
neutro y la propagación de errores. `PaymentServiceTest` comprueba que el rechazo
no tenga efectos, y `PaymentControllerTest` comprueba el `403` y JSON público.
Se conservan las regresiones de detalle, simulación, comprobantes, historia,
cancelación y devolución del PR.

Ejecutar con JDK 25 desde la raíz:

```powershell
./mvnw.cmd -f ms-debt/pom.xml clean test
./mvnw.cmd -f ms-payments/pom.xml clean test
```

Estas pruebas usan dependencias simuladas y HTTP local; no constituyen una prueba
E2E en navegador ni una validación de pasarelas reales. El plan oficial de
SEG-DAT-007 no está en el repositorio; este inventario documenta el contrato y la
regresión automatizada, sin declarar actualizado ese plan externo.

## Evidencia de recuperación, 20 de noviembre de 2026

Base integrada: `dd5cd1de0240a1f5bed1700523f8732274cb7bdd`. Se conservan el PR #404,
sus tres commits remotos y las integraciones locales con main. El stash previo
se recuperó mediante `apply`, sin eliminarlo. README raíz idéntico a main.

La baseline limpia integrada ejecutó 584 pruebas de ms-debt y 337 de ms-payments,
sin fallos, errores ni omisiones. El primer intento incremental de ms-debt coincidió
con la integración de main y dejó clases anteriores de ResumenResponse; se descartó
como evidencia y se repitió con `clean test`, correctamente.

Validación final con JDK 25, Maven Wrapper, fecha 2026-11-20, America/Santiago:

| Comando | Pruebas | Fallos | Errores | Omitidas |
| --- | ---: | ---: | ---: | ---: |
| `./mvnw.cmd -B -f ms-debt/pom.xml clean test` | 595 | 0 | 0 | 0 |
| `./mvnw.cmd -B -f ms-payments/pom.xml clean test` | 345 | 0 | 0 | 0 |
| `node --test pruebas/secretos.test.cjs` con gitleaks existente | 34 | 0 | 0 | 0 |

Los logs locales son `final-debt-321-recovery.log`, `final-payments-321-recovery.log`
y `final-secrets-321-recovery.log`; Surefire genera XML en cada `target/surefire-reports`.
No se agregan logs completos al repositorio. Estos son resultados de esta ejecución,
no una actualización de los totales globales del proyecto.

### SEG-DAT-007 reproducible

- Precondiciones: perfil test, JWT de pruebas firmado y validado por SecurityConfig;
  repositorios simulados con entidades controladas, sin bases existentes.
- Datos ficticios: deuda 40 existente de otro deudor/empresa y deuda 41 ausente;
  visitante DEBTOR y visitante CREDITOR con su identidad registrada.
- Ejecutar `./mvnw.cmd -B -f ms-debt/pom.xml -Dtest=ResourceVisibilitySecurityTest test`.
- Caso `seg_dat_007_compara_json_completo_con_autorizacion_real_para_ambos_roles`:
  cada JWT pide `GET /api/debts/40` y `/41`, y repite con `/simulate`.
- Esperado y observado: 403 y cuerpo completo `{"error":"No puedes ver esta deuda"}`,
  idénticos byte por byte dentro de cada par, sin datos del recurso. PASS automatizado.
- Se ejecutan DebtService y su autorización reales; no se configura un mock del
  servicio que devuelva directamente un 403. Sin sesión: 401. ADMIN: 403 antes de buscar.
- La agencia que entregó la cartera conserva acceso aunque no sea acreedora.
- Comprobantes: evento 50 de pago ajeno, 51 ausente y 52 de otro tipo devuelven el
  mismo JSON para ambos roles, pasando por ComprobanteService, HistorialService y
  DebtService reales. Un acreedor autorizado conserva acceso al evento de pago.

`CheckoutSecurityTest` ejecuta JWT, PaymentController, PaymentService y DebtClient
reales, con un servidor HTTP local que implementa el contrato de ms-debt. Comprueba
clave interna, RUT del JWT y seis combinaciones deuda 40/41 y cuotas 12/98/999;
ninguna consulta o escritura de pagos/eventos ni llamada a pasarela ocurre al rechazar.
El contrato de ms-debt se comprueba por separado con su controlador y servicio reales
en ResourceVisibilitySecurityTest, y las reglas de cuotas y montos en DebtServiceTest.
El pago propio controlado recibe 410000 del snapshot; el navegador no puede mandar
RUT ni importe. CREDITOR y ADMIN no consultan el snapshot; sin sesión recibe 401.
Esto es integración parcial con HTTP local, no dos servicios con MySQL real.

Docker: NOT RUN. `docker info` no pudo conectar con dockerDesktopLinuxEngine.
No se crearon ni eliminaron bases, volúmenes o cobros reales. No se comprobó
persistencia en tablas reales; la ausencia de efectos se verifica con mocks.
Sin cambios de frontend, revisión por lectura de `comoError`; no recorrido de navegador.

### Inventario de autorización y evidencia

La columna «contrato ajeno/ausente» describe la política auditada. «Evidencia»
distingue la ejecución de la autorización común de una prueba HTTP específica;
no significa que todas las operaciones se hayan recorrido en un sistema desplegado.

| Ruta privada | Rol | Autorización | Contrato ajeno/ausente | Evidencia ejecutada |
| --- | --- | --- | --- | --- |
| `GET /api/debts/{id}` | DEBTOR, CREDITOR | requireVisible | 403 deuda / 403 deuda | SEG-DAT-007 HTTP, servicio real |
| `GET /api/debts/{id}/simulate` | DEBTOR, CREDITOR | requireVisible | 403 deuda / 403 deuda | SEG-DAT-007 HTTP, servicio real |
| `POST /api/debts/{id}/repact` | DEBTOR | applyRepact → requireVisible | 403 deuda / 403 deuda | Suite de repactación y autorización común; auditoría del orden |
| `POST /api/debts/{id}/disputa` | DEBTOR | DisputaService → requireVisible | 403 deuda / 403 deuda | Suite DisputaService y autorización común; auditoría del orden |
| `POST /api/debts/{id}/disputa/resolucion` | CREDITOR | DisputaService → requireVisible | 403 deuda / 403 deuda | Suite DisputaService y autorización común; auditoría del orden |
| `POST /api/debts/{id}/codigo` | CREDITOR | AccesoService → requireVisible | 403 deuda / 403 deuda | Suite AccesoService y autorización común; auditoría del orden |
| `GET /api/debts/{id}/certificate` | DEBTOR, CREDITOR | CertificateService → requireVisible | 403 deuda / 403 deuda | Suite de certificados y autorización común; auditoría del orden |
| `GET /api/debts/pagos/{id}/comprobante` | DEBTOR, CREDITOR | HistorialService → requireVisible | 403 deuda / 403 deuda | HTTP con servicios reales y eventos controlados |
| `GET /api/payments/{id}` | DEBTOR, CREDITOR | visible → buscarPrivado | 403 pago / 403 pago | PaymentServiceTest, matriz SEG-DAT-007 |
| `GET /api/payments/{id}/historia` | DEBTOR, CREDITOR | visible → buscarPrivado | 403 pago / 403 pago | PaymentServiceTest, matriz SEG-DAT-007 |
| `POST /api/payments/{id}/cancelar` | DEBTOR | paraCerrar + propietario | 403 pago / 403 pago | PaymentServiceTest, matriz SEG-DAT-007 |
| `POST /api/payments/{id}/devolucion` | CREDITOR | buscarPrivado + cartera/acreedor | 403 pago / 403 pago | PaymentServiceTest, matriz SEG-DAT-007 |
| `POST /api/payments/checkout` | DEBTOR | snapshotCheckout + propietario | 403 deuda / 403 deuda | Controladores, servicios y cliente HTTP local |

«403 deuda» significa `No puedes ver esta deuda`; «403 pago», `No puedes ver este pago`.
Para recursos propios se conservan los permisos y conflictos de negocio de cada
operación; las suites ejercitan montos, intereses, descuentos, UF, cuotas pagadas,
orden, idempotencia, cancelación, devoluciones y pasarelas simuladas.
Un rol prohibido para toda una operación tiene su rechazo independiente del ID.
La revisión no encontró otra ruta privada por ID que permita inferir existencia.

Excepciones: `/public/**` conserva firmas de acceso y validaciones de retorno;
webhooks conservan firmas; `/internal/**` conserva clave interna y red privada;
admin conserva ADMIN, integración v1 su API key y alcance. Claves, mandatos y
campañas conservan búsquedas acotadas a empresa con 404 uniforme. No se amplía
el Gateway ni se cambia ninguna de estas políticas.

### Plan oficial

`PLAN_OFICIAL_ACTUALIZADO = NO`.
`MOTIVO = sin autorización para actualizar documentos académicos oficiales`.

El plan oficial se localizó en Drive: PTY4614_800D_Plan_de_Pruebas.xlsx,
https://docs.google.com/spreadsheets/d/1t5BvHTmp3gX-kA6NdwA7IRMjCgnO8CGC/edit
Caso SEG-DAT-007, hoja Pruebas de seguridad; índice exportado 80 (fila 82
observada en el plan). Estado leído: No ejecutado / Pendiente. Criterio:
403 indistinguible para un recurso ajeno e inexistente. Las pruebas del PR
aportan evidencia automatizada; E2E con MySQL sigue NOT RUN y pasa al lote 3.
La ubicación deberá verificarse de nuevo antes de actualizar el registro.
