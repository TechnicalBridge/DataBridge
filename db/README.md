# Las bases de DataBridge

Tres bases, una por servicio. Cada una vive con su servicio y se migra con **Flyway**.

| Base | Servicio | Qué guarda |
| --- | --- | --- |
| `tb_auth` | ms-auth | Códigos de acceso, magic links de respaldo, personal de las organizaciones |
| `tb_debt` | ms-debt | Organizaciones, mandatos, campañas, carteras recibidas, deudores, deudas, cuotas y la bandeja de salida de eventos |
| `tb_payments` | ms-payments | Pagos, su libro de eventos, el valor de la UF y los avisos hacia ms-debt |

Las migraciones están en `<servicio>/src/main/resources/db/migration/`. La línea base es
`V1__esquema_inicial.sql` y **no se edita nunca más**: todo cambio posterior entra como `V2`, `V3`…
Es la diferencia con lo que había antes, donde `ddl-auto: update` cambiaba el esquema en silencio y
nadie podía decir en qué estado estaba una base.

En `tb_auth`, `V6__codigo_y_enlace_del_mismo_acceso.sql` agrega a `magic_links` la huella del código
solicitado, sus intentos y si el enlace está habilitado. Código y enlace comparten consumo y
vencimiento. Es aditiva: los enlaces anteriores conservan `code_hash` vacío y siguen habilitados.
Respaldar antes de aplicarla. Para volver al código anterior se conserva V6 en el historial de
Flyway; no se borran columnas ni se edita una migración aplicada.

## Levantar las bases

```bash
docker compose up -d db-auth db-debt db-payments
```

Cada base vive en **su propio MySQL** ([#238](https://github.com/TechnicalBridge/TB_web/issues/238)), con su volumen, sus
claves y su puerto. Si una se cae, se llena o se restaura, las otras dos siguen:

| Servicio | Contenedor | Base | Puerto en el equipo | Volumen | Claves en el `.env` |
| --- | --- | --- | --- | --- | --- |
| `db-auth` | `tbridge-db-auth` | `tb_auth` | 3308 (`DB_AUTH_PORT`) | `tb_auth_data` | `DB_AUTH_ROOT_PASSWORD`, `DB_AUTH_PASSWORD` |
| `db-debt` | `tbridge-db-debt` | `tb_debt` | 3309 (`DB_DEBT_PORT`) | `tb_debt_data` | `DB_DEBT_ROOT_PASSWORD`, `DB_DEBT_PASSWORD` |
| `db-payments` | `tbridge-db-payments` | `tb_payments` | 3310 (`DB_PAYMENTS_PORT`) | `tb_payments_data` | `DB_PAYMENTS_ROOT_PASSWORD`, `DB_PAYMENTS_PASSWORD` |

La primera vez, cada una crea su base vacía y le da permisos al usuario de la aplicación (`MYSQL_USER`, el
mismo nombre en las tres porque las vistas lo tienen como `DEFINER`, pero con otra clave en cada una). Cada
servicio corre sus migraciones al arrancar. Las claves las genera `preparar-env.ps1`, y con la de una base
no se entra a las otras.

### Si ya tenías datos de antes de #238

Hasta #238 las tres bases vivían en un solo MySQL (`tbridge-db`, con el volumen `tbridge_db_data`). Para
pasar esos datos a las tres, una sola vez por equipo y antes de levantar los servicios:

```powershell
powershell -ExecutionPolicy Bypass -File .\preparar-env.ps1
powershell -ExecutionPolicy Bypass -File .\db\separar-bases.ps1
docker compose --profile app up -d --build --wait --remove-orphans
```

`separar-bases.ps1` abre el volumen de antes sin tocarlo, vuelca cada base y la carga en la suya, y compara
las filas de cada tabla. No pisa una base nueva que ya tenga tablas: si ya levantaste todo antes de correrlo,
las tablas vacías que creó Flyway se reemplazan por las de antes con `-Forzar`. El volumen de antes
queda intacto: cuando compruebes que todo está bien, se borra con `docker volume rm` y el nombre que muestra
el script.

## Por qué tres bases y no una

Cada servicio manda sobre lo suyo y **ninguno lee la base de otro**. Por eso no hay claves foráneas
que crucen de una base a otra: `payments.debt_id` es una referencia lógica, y quien la valida es el
servicio de deudas.

El precio es que no se puede hacer un `JOIN` entre pagos y deudas en una sola consulta. Se paga a
propósito: a cambio, cambiar el esquema de pagos no obliga a tocar el de deudas, que es lo que hace
que cada servicio se pueda trabajar y desplegar solo.

## Qué cambia respecto del modelo anterior

| Antes | Ahora | Por qué |
| --- | --- | --- |
| H2 en archivo, `ddl-auto: update` | MySQL 8.4 con Flyway y `ddl-auto: validate` | El esquema deja de cambiar solo; si el código y la base no calzan, el servicio no arranca |
| El deudor y el acreedor, texto suelto en la deuda | Tablas propias con RUT | Sin RUT no hay cómo cruzar una deuda con quien la entregó |
| Sin `creditor_id` en la deuda | `creditor_id` + índice | **Es lo que arregla la fuga**: antes cualquier acreedor veía las deudas de todos, porque no había por qué filtrar |
| `status` del pago sobreescrito | `payment_events`, solo inserción | Antes solo se sabía cómo terminó un pago, nunca cómo llegó ahí |
| Idempotencia con un `if` en Java | `UNIQUE (gateway, gateway_txn_id)` | Un webhook reintentado ya no puede abonar dos veces |
| Auditoría como texto armado a mano | Columnas y `JSON` | `"pago_exitoso 50000 CLP via webpay"` no se puede sumar ni filtrar |
| Avisos enviados dentro de la transacción | Bandeja de salida con reintentos | Si el otro lado está caído, el aviso sale cuando vuelva en vez de perderse |

## Dónde vive el borde de integración

El contrato (`docs/integracion/`) lo atiende **ms-debt**, no un servicio aparte. Decisión I11,
que estaba abierta.

El motivo es que todo lo que el contrato toca —organizaciones, mandatos, campañas, lotes, deudas—
son agregados de ms-debt. Un servicio aparte tendría que pedirle a ms-debt casi cada dato para
validar una cartera, o guardar su propia copia de todo. La separación quedaría en el nombre, y el
costo de operar un quinto servicio sería real.

Lo que sí se mantiene separado es el **paquete**: la traducción del contrato vive en su propio
módulo dentro de ms-debt, para que el día que salga una v2 se toque eso y no el dominio.

## Una nota sobre secretos

`subscriptions.secret` se guarda en claro **porque hay que firmar con él**, y una huella no sirve
para firmar. En producción esa columna va cifrada con una llave que no está en la base. Las claves
de API sí se guardan como huella, porque esas solo se comparan.
