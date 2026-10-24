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
docker compose up -d mysql
```

Crea el motor con las tres bases vacías. Cada servicio corre sus migraciones al arrancar.

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
