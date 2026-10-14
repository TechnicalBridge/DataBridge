# Diagrama entidad-relación

Vista lógica del [modelo 4+1](README.md), por el lado de los datos: qué se guarda y cómo se relaciona.

DataBridge no tiene una base, tiene **tres, una por servicio**, en el mismo MySQL 8.4: 25 tablas, 3 vistas y 26 llaves foráneas en total. Cada servicio es dueño de la suya y ninguno lee la de otro: si ms-payments necesita saber cuánto se debe, se lo pregunta a ms-debt. Por eso **entre las tres no hay llaves foráneas**; se unen por el RUT y por el id de la deuda ([más abajo](#cómo-se-unen-las-tres-bases)).

| Base | Servicio | Qué guarda | Tablas | Migraciones de Flyway |
| --- | --- | --- | --- | --- |
| [`tb_auth`](#tb_auth--el-acceso) | ms-auth | El acceso | 6 | `V1` esquema inicial · `V2` sesiones · `V3` enlace pedido · `V4` roles admin y usuario · `V5` bitacora del panel |
| [`tb_debt`](#tb_debt--la-cartera) | ms-debt | La cartera | 15 | `V1` esquema inicial · `V2` recordatorios · `V3` secretos cifrados · `V4` intereses · `V5` descuento · `V6` bitacora del panel · `V7` contactos |
| [`tb_payments`](#tb_payments--los-cobros) | ms-payments | Los cobros | 4 | `V1` esquema inicial · `V2` pago duplicado · `V3` interes del pago · `V4` webpay una vez · `V5` descuento del pago |

**Cómo leerlos.** Cada caja es una tabla, con el tipo y el nombre de cada columna. `PK` es la llave primaria, `FK` una llave foránea y `UK` un valor que no se repite. Una columna es obligatoria salvo que diga *opcional*. `decimal(18_2)` es un número de 18 dígitos con 2 decimales. En las líneas, `||` es *uno*, `o{` es *cero o muchos* y `|o` es *cero o uno*.

Los diagramas salen del esquema real: el que dejan las migraciones de `ms-*/src/main/resources/db/migration` al aplicarse. Cada servicio arranca con `ddl-auto=validate`, así que si una entidad y su tabla dejan de calzar, el servicio no parte.

---

## `tb_auth` — el acceso

La base de **ms-auth**. Quién entra y cómo. **No hay tabla de deudores**: el deudor no tiene cuenta, entra con su RUT y un código. De nada sensible se guarda el original: del código, del enlace y de la llave de renovación queda solo su huella (SHA-256), y de la IP también.

```mermaid
erDiagram
    direction LR
    access_codes {
        bigint id PK
        char(64) code_hash UK "SHA-256 de RUT y código: el código no se guarda"
        varchar(12) debtor_rut "el RUT del deudor (debtors.rut, en tb_debt)"
        json channels "por dónde se mandó"
        datetime issued_at
        datetime expires_at
        datetime consumed_at "opcional · cuándo se usó"
        smallint attempts "intentos fallidos"
        smallint max_attempts "el tope: 5"
        varchar(64) issued_for "opcional"
    }
    access_log {
        bigint id PK
        varchar(12) debtor_rut "opcional · vacío si es del personal"
        varchar(12) method "code o magic_link"
        varchar(12) outcome "granted, expired, invalid, exhausted, sent o refused"
        char(64) ip_hash "opcional · SHA-256 de la IP: la IP no se guarda"
        datetime occurred_at
    }
    admin_log {
        bigint id PK
        varchar(254) admin_email "el admin que lo hizo"
        varchar(40) action "persona_creada, admin_creado, persona_deshabilitada o persona_habilitada"
        varchar(12) org_rut "opcional · la empresa sobre la que se hizo"
        varchar(254) subject "opcional · a qué o a quién"
        datetime occurred_at
    }
    magic_links {
        bigint id PK
        char(64) token_hash UK "SHA-256 del enlace"
        varchar(12) debtor_rut "opcional · solo si el enlace es de un deudor"
        varchar(254) email
        datetime issued_at
        datetime expires_at
        datetime consumed_at "opcional · cuándo se usó"
    }
    sessions {
        bigint id PK
        char(36) family_id "las llaves de un mismo inicio de sesión"
        char(64) refresh_hash UK "SHA-256 de la llave de renovación"
        varchar(10) role "DEBTOR, CREDITOR o ADMIN"
        varchar(12) rut "opcional · el del deudor"
        varchar(254) email "opcional · el del usuario de una empresa o del admin"
        datetime issued_at
        datetime expires_at
        datetime rotated_at "opcional · cuándo se cambió por la siguiente"
        datetime revoked_at "opcional · cuándo se revocó"
        char(64) ip_hash "opcional · SHA-256 de la IP"
    }
    staff_users {
        bigint id PK
        varchar(12) org_rut "opcional · su empresa (organizations.rut, en tb_debt); el admin no tiene"
        varchar(254) email UK
        varchar(160) full_name
        varchar(10) role "usuario o admin"
        datetime created_at
        datetime last_login_at "opcional"
        datetime disabled_at "opcional · si se le quitó el acceso"
    }
```

Las 6 tablas no se relacionan entre sí con llaves foráneas: cada una se consulta por su propia huella, o por el RUT o el correo de alguien.

| Tabla | Qué guarda |
| --- | --- |
| `access_codes` | El código con que entra un deudor. Un solo uso, con vencimiento y con tope de intentos. |
| `access_log` | Cada intento de entrar y cada enlace pedido: por dónde, cómo resultó y desde qué origen. |
| `admin_log` | Lo que un admin de DataBridge hizo con las personas desde su panel: altas, y accesos quitados y devueltos. |
| `magic_links` | El enlace de un solo uso al correo: como entran el usuario de una empresa y el admin, y el respaldo del deudor. |
| `sessions` | Las llaves de renovación de cada sesión, en familias. Es lo único de una sesión que se puede revocar. |
| `staff_users` | Las personas con cuenta: los usuarios de las empresas y los admin de DataBridge. |

<details>
<summary>Las 14 reglas que exige la base (<code>CHECK</code>)</summary>

Lo que la aplicación valida, la base lo vuelve a exigir: aunque alguien escriba directo en ella, no puede dejar un dato que rompa estas reglas.

| Tabla | Regla | Qué exige |
| --- | --- | --- |
| `access_codes` | `ck_access_code_intentos` | `(attempts <= max_attempts)` |
| `access_codes` | `ck_access_code_rut` | `regexp_like(debtor_rut,'^[0-9]{7,8}-[0-9K]$')` |
| `access_codes` | `ck_access_code_vigencia` | `(expires_at > issued_at)` |
| `access_log` | `ck_access_log_method` | `(method in ('code','magic_link'))` |
| `access_log` | `ck_access_log_outcome` | `(outcome in ('granted','expired','invalid','exhausted','sent','refused'))` |
| `admin_log` | `ck_admin_log_action` | `(action in ('persona_creada','admin_creado','persona_deshabilitada','persona_habilitada'))` |
| `magic_links` | `ck_magic_link_vigencia` | `(expires_at > issued_at)` |
| `sessions` | `ck_session_identidad` | `(((role = 'DEBTOR') and (rut is not null)) or ((role in ('CREDITOR','ADMIN')) and (email is not null)))` |
| `sessions` | `ck_session_role` | `(role in ('DEBTOR','CREDITOR','ADMIN'))` |
| `sessions` | `ck_session_rut` | `((rut is null) or regexp_like(rut,'^[0-9]{7,8}-[0-9K]$'))` |
| `sessions` | `ck_session_vigencia` | `(expires_at > issued_at)` |
| `staff_users` | `ck_staff_user_admin_sin_empresa` | `(((role = 'admin') and (org_rut is null)) or ((role = 'usuario') and (org_rut is not null)))` |
| `staff_users` | `ck_staff_user_role` | `(role in ('usuario','admin'))` |
| `staff_users` | `ck_staff_user_rut` | `regexp_like(org_rut,'^[0-9]{7,8}-[0-9K]$')` |

</details>

---

## `tb_debt` — la cartera

La base de **ms-debt**. Las empresas, lo que se les debe y todo lo que pasa con cada deuda. El saldo y la mora **no se guardan, se calculan**: el saldo es la suma de las cuotas pendientes y la mora sale de la tasa de la deuda y de los días de atraso.

### Las tablas y sus relaciones

Primero sin columnas, para ver la forma:

```mermaid
erDiagram
    organizations ||--o{ api_keys : "se identifica con"
    campaigns |o--o{ batches : "recibe"
    organizations ||--o{ batches : "es acreedora en (creditor_id)"
    organizations ||--o{ batches : "entrega (sender_id)"
    organizations ||--o{ campaigns : "ejecuta (agency_id)"
    organizations ||--o{ campaigns : "encarga (creditor_id)"
    campaigns |o--o{ contacts : "ordena cada toque de"
    debts ||--o{ contacts : "motiva"
    installments |o--o| contacts : "se recuerda con"
    debts ||--o{ debt_charges : "se desglosa en"
    debts ||--o{ debt_events : "deja historia en"
    campaigns |o--o{ debts : "gestiona"
    organizations ||--o{ debts : "es acreedora de"
    debtors ||--o{ debts : "debe"
    batches ||--o{ debts : "trae por primera vez (first_batch_id)"
    batches ||--o{ debts : "actualiza (last_batch_id)"
    mandates |o--o{ debts : "ampara"
    debts ||--o{ installments : "se paga en"
    repactations |o--o{ installments : "arma"
    organizations ||--o{ mandates : "cobra por otros en (agency_id)"
    organizations ||--o{ mandates : "encarga su cobranza en (creditor_id)"
    subscriptions ||--o{ outbox : "recibe"
    debts ||--o{ repactations : "se repacta en"
    organizations ||--o{ subscriptions : "se suscribe con"
```

### Con todas sus columnas

```mermaid
erDiagram
    organizations ||--o{ api_keys : "se identifica con"
    campaigns |o--o{ batches : "recibe"
    organizations ||--o{ batches : "es acreedora en (creditor_id)"
    organizations ||--o{ batches : "entrega (sender_id)"
    organizations ||--o{ campaigns : "ejecuta (agency_id)"
    organizations ||--o{ campaigns : "encarga (creditor_id)"
    campaigns |o--o{ contacts : "ordena cada toque de"
    debts ||--o{ contacts : "motiva"
    installments |o--o| contacts : "se recuerda con"
    debts ||--o{ debt_charges : "se desglosa en"
    debts ||--o{ debt_events : "deja historia en"
    campaigns |o--o{ debts : "gestiona"
    organizations ||--o{ debts : "es acreedora de"
    debtors ||--o{ debts : "debe"
    batches ||--o{ debts : "trae por primera vez (first_batch_id)"
    batches ||--o{ debts : "actualiza (last_batch_id)"
    mandates |o--o{ debts : "ampara"
    debts ||--o{ installments : "se paga en"
    repactations |o--o{ installments : "arma"
    organizations ||--o{ mandates : "cobra por otros en (agency_id)"
    organizations ||--o{ mandates : "encarga su cobranza en (creditor_id)"
    subscriptions ||--o{ outbox : "recibe"
    debts ||--o{ repactations : "se repacta en"
    organizations ||--o{ subscriptions : "se suscribe con"
    admin_log {
        bigint id PK
        varchar(254) admin_email "el admin que lo hizo"
        varchar(40) action
        varchar(12) org_rut "opcional · la empresa sobre la que se hizo"
        varchar(254) subject "opcional · a qué o a quién"
        datetime occurred_at
    }
    api_keys {
        bigint id PK
        bigint organization_id FK
        varchar(80) name
        char(64) key_hash UK "SHA-256 de la clave"
        varchar(12) prefix "para reconocerla sin revelarla"
        datetime created_at
        datetime last_used_at "opcional"
        datetime revoked_at "opcional · desde cuándo no sirve"
    }
    batches {
        bigint id PK
        bigint sender_id FK "no se repite junto con external_id"
        bigint creditor_id FK
        bigint campaign_id FK "opcional"
        varchar(64) external_id "el id que le puso quien la envió · no se repite junto con sender_id"
        date cut_off "la fecha de corte de la cartera"
        varchar(10) source "api o file"
        varchar(12) status "processed o rejected"
        int received_count
        int accepted_count
        int rejected_count
        char(64) payload_hash "para reconocer un reenvío idéntico"
        json response "lo que se respondió, deuda por deuda"
        datetime received_at
    }
    campaigns {
        bigint id PK
        bigint agency_id FK "no se repite junto con external_id"
        bigint creditor_id FK
        varchar(64) external_id "el id que le puso la agencia · no se repite junto con agency_id"
        varchar(120) name
        date starts_on
        date ends_on "opcional"
        json channels "por dónde se contacta"
        smallint attempts "entre 1 y 10"
        json cadence_days "opcional · en qué días se le escribe al deudor"
        json mora_discount "opcional · % de descuento por tramo de mora"
        varchar(10) status "running, paused o finished"
        datetime created_at
    }
    contacts {
        bigint id PK
        bigint debt_id FK "la deuda por la que se le escribió; el deudor es el de esa deuda · no se repite junto con campaign_id y touch_number"
        varchar(12) kind "invitation, campaign, reminder o resend"
        varchar(10) channel "por dónde se le escribió: hoy, solo email"
        varchar(254) destination "opcional · a dónde, con la dirección tapada"
        bigint campaign_id FK "opcional · solo en un toque de campaña · no se repite junto con debt_id y touch_number"
        smallint touch_number "opcional · qué toque es de esa campaña para esa deuda: 1, 2… · no se repite junto con debt_id y campaign_id"
        bigint installment_id FK, UK "opcional · solo en un recordatorio: la cuota que se avisó"
        datetime occurred_at
    }
    debt_charges {
        bigint id PK
        bigint debt_id FK
        varchar(120) concept
        varchar(7) period "opcional · el mes del cargo"
        decimal(18_2) amount
        date due_date
    }
    debt_events {
        bigint id PK
        bigint debt_id FK
        varchar(30) type "registered, updated, withdrawn, portal_entered, repacted, payment_applied, settled o disputed"
        varchar(10) actor "debtor, creditor, agency o system"
        decimal(18_2) amount "opcional"
        varchar(3) currency "opcional · CLP o UF"
        varchar(80) reference "opcional · a qué se refiere: el pago, la cartera, el motivo"
        json detail "opcional · lo que no calza en una columna"
        datetime occurred_at
    }
    debtors {
        bigint id PK
        varchar(12) rut UK
        varchar(10) kind "person o company"
        varchar(160) full_name
        varchar(254) email "opcional"
        varchar(20) phone "opcional"
        boolean reminders "si quiere el recordatorio de sus cuotas"
        datetime created_at
        datetime updated_at
    }
    debts {
        bigint id PK
        bigint creditor_id FK "no se repite junto con external_id"
        bigint debtor_id FK
        varchar(64) external_id "el id que le puso el acreedor · no se repite junto con creditor_id"
        varchar(3) currency "CLP o UF"
        varchar(200) concept
        json refs "opcional · referencias del acreedor"
        decimal(18_2) original_amount "el monto con que llegó"
        decimal(5_2) interest_rate "opcional · % mensual que pactó el acreedor; vacío, sin interés"
        varchar(12) status "open, repacted, paid, withdrawn o disputed"
        bigint mandate_id FK "opcional"
        bigint campaign_id FK "opcional"
        bigint first_batch_id FK "la cartera que la trajo"
        bigint last_batch_id FK "la última cartera que la actualizó"
        varchar(30) withdrawn_reason "opcional · por qué se retiró"
        datetime created_at
        datetime updated_at
    }
    installments {
        bigint id PK
        bigint debt_id FK "no se repite junto con number"
        bigint repactation_id FK "opcional · vacío en la cuota original"
        smallint number "no se repite junto con debt_id"
        date due_date
        decimal(18_2) amount
        decimal(18_2) interest_amount "el interés del convenio dentro de la cuota"
        varchar(10) status "pending, paid o void"
        datetime paid_at "opcional"
    }
    mandates {
        bigint id PK
        bigint agency_id FK "no se repite junto con creditor_id y valid_from"
        bigint creditor_id FK "no se repite junto con agency_id y valid_from"
        date valid_from "no se repite junto con agency_id y creditor_id"
        date valid_to "opcional"
        smallint max_overdue_days "días de mora hasta donde cobra la agencia"
        decimal(5_2) max_mora_discount "opcional · % máximo de la mora que se puede condonar"
        varchar(10) status "active o ended"
        varchar(160) declared_by "opcional · quién lo declaró"
        datetime created_at
    }
    organizations {
        bigint id PK
        varchar(12) rut UK
        varchar(160) legal_name
        varchar(120) trade_name
        varchar(10) kind "creditor, agency o both"
        varchar(10) status "active o suspended"
        datetime created_at
        datetime updated_at
    }
    outbox {
        bigint id PK
        char(36) event_id "el mismo evento, una fila por suscriptor · no se repite junto con subscription_id"
        bigint subscription_id FK "no se repite junto con event_id"
        varchar(30) type
        json payload
        datetime occurred_at
        varchar(10) status "pending, delivered o failed"
        smallint attempts
        datetime next_attempt_at "cuándo se reintenta"
        datetime delivered_at "opcional"
        varchar(300) last_error "opcional"
    }
    repactations {
        bigint id PK
        bigint debt_id FK
        smallint months "entre 1 y 24"
        decimal(18_2) monthly_amount "el valor de la cuota"
        decimal(5_2) interest_rate "opcional"
        decimal(18_2) principal "opcional · lo repactado: capital más mora"
        varchar(3) currency "CLP o UF"
        datetime accepted_at
        datetime superseded_at "opcional · cuándo lo reemplazó otro convenio"
    }
    subscriptions {
        bigint id PK
        bigint organization_id FK "no se repite junto con url"
        varchar(300) url "no se repite junto con organization_id"
        varchar(255) secret "cifrado, con una llave fuera de la base"
        json events "opcional · cuáles quiere; vacío, todos"
        boolean active
        datetime created_at
    }
```

| Tabla | Qué guarda |
| --- | --- |
| `admin_log` | Lo que un admin de DataBridge hizo con las empresas desde su panel: las que dio de alta. |
| `api_keys` | Las claves de API con que el sistema de una empresa entrega cartera. |
| `batches` | Cada cartera recibida, por la API o por archivo, con su huella para no procesarla dos veces y la respuesta que se dio. |
| `campaigns` | La estrategia de contacto de una agencia para la cartera de un acreedor. La define la agencia y la ejecuta DataBridge. |
| `contacts` | Cada vez que DataBridge le escribió a un deudor: la invitación, un toque de campaña, el recordatorio de una cuota o el código que reenvía la empresa. Es el registro de gestiones de cobranza que pide la ley, y con él se cuenta el límite de dos por semana. |
| `debt_charges` | El desglose con que llegó la deuda: cada cargo con su monto y su vencimiento. |
| `debt_events` | La historia de cada deuda: qué pasó, quién lo hizo, cuánto y cuándo. Lo que se le escribe al deudor va aparte, en `contacts`. |
| `debtors` | Quien debe. Una fila por RUT, aunque le deba a varios acreedores. |
| `debts` | Lo que un deudor le debe a un acreedor. |
| `installments` | Las cuotas por pagar. Una deuda sin convenio tiene una sola; al repactar, las pendientes se anulan y se emiten las del convenio. |
| `mandates` | Una agencia cobra por cuenta de un acreedor: desde cuándo, hasta cuántos días de mora y con qué tope de descuento. |
| `organizations` | Las empresas: los acreedores y las agencias de cobranza. |
| `outbox` | La bandeja de salida: cada evento por entregar a cada suscriptor, con sus reintentos. |
| `repactations` | Cada convenio que el deudor aceptó. Uno nuevo no pisa al anterior: lo marca como reemplazado. |
| `subscriptions` | A qué dirección se le avisan los eventos a una empresa, y con qué secreto se firman. |

| Vista | Qué calcula |
| --- | --- |
| `v_creditor_portfolio` | Por acreedor: cuántas deudas tiene, cuántas activas y pagadas, y el saldo en pesos y en UF. |
| `v_debt_balance` | Por deuda: el saldo (cuotas pendientes), lo pagado y el próximo vencimiento. |

<details>
<summary>Las 39 reglas que exige la base (<code>CHECK</code>)</summary>

Lo que la aplicación valida, la base lo vuelve a exigir: aunque alguien escriba directo en ella, no puede dejar un dato que rompa estas reglas.

| Tabla | Regla | Qué exige |
| --- | --- | --- |
| `admin_log` | `ck_admin_log_action` | `(action = 'empresa_creada')` |
| `batches` | `ck_batch_source` | `(source in ('api','file'))` |
| `batches` | `ck_batch_status` | `(status in ('processed','rejected'))` |
| `campaigns` | `ck_campaign_attempts` | `(attempts between 1 and 10)` |
| `campaigns` | `ck_campaign_fechas` | `((ends_on is null) or (ends_on >= starts_on))` |
| `campaigns` | `ck_campaign_status` | `(status in ('running','paused','finished'))` |
| `contacts` | `ck_contact_campaign` | `(((kind = 'campaign') and (campaign_id is not null) and (touch_number is not null)) or ((kind <> 'campaign') and (campaign_id is null) and (touch_number is null)))` |
| `contacts` | `ck_contact_channel` | `(channel = 'email')` |
| `contacts` | `ck_contact_kind` | `(kind in ('invitation','campaign','reminder','resend'))` |
| `contacts` | `ck_contact_reminder` | `(((kind = 'reminder') and (installment_id is not null)) or ((kind <> 'reminder') and (installment_id is null)))` |
| `contacts` | `ck_contact_touch` | `((touch_number is null) or (touch_number >= 1))` |
| `debt_charges` | `ck_charge_amount` | `(amount > 0)` |
| `debt_events` | `ck_debt_event_actor` | `(actor in ('debtor','creditor','agency','system'))` |
| `debt_events` | `ck_debt_event_currency` | `((currency is null) or (currency in ('CLP','UF')))` |
| `debt_events` | `ck_debt_event_type` | `(type in ('registered','updated','withdrawn','portal_entered','repacted','payment_applied','settled','disputed'))` |
| `debtors` | `ck_debtor_canal` | `((email is not null) or (phone is not null))` |
| `debtors` | `ck_debtor_kind` | `(kind in ('person','company'))` |
| `debtors` | `ck_debtor_rut` | `regexp_like(rut,'^[0-9]{7,8}-[0-9K]$')` |
| `debts` | `ck_debt_amount` | `(original_amount > 0)` |
| `debts` | `ck_debt_clp_entero` | `((currency <> 'CLP') or (original_amount = round(original_amount,0)))` |
| `debts` | `ck_debt_currency` | `(currency in ('CLP','UF'))` |
| `debts` | `ck_debt_interest_rate` | `((interest_rate is null) or (interest_rate > 0))` |
| `debts` | `ck_debt_status` | `(status in ('open','repacted','paid','withdrawn','disputed'))` |
| `installments` | `ck_installment_amount` | `(amount > 0)` |
| `installments` | `ck_installment_interest` | `((interest_amount >= 0) and (interest_amount <= amount))` |
| `installments` | `ck_installment_paid` | `(((status = 'paid') and (paid_at is not null)) or ((status <> 'paid') and (paid_at is null)))` |
| `installments` | `ck_installment_status` | `(status in ('pending','paid','void'))` |
| `mandates` | `ck_mandate_fechas` | `((valid_to is null) or (valid_to >= valid_from))` |
| `mandates` | `ck_mandate_mora_discount` | `((max_mora_discount is null) or ((max_mora_discount >= 0) and (max_mora_discount <= 100)))` |
| `mandates` | `ck_mandate_partes` | `(agency_id <> creditor_id)` |
| `mandates` | `ck_mandate_status` | `(status in ('active','ended'))` |
| `organizations` | `ck_organization_kind` | `(kind in ('creditor','agency','both'))` |
| `organizations` | `ck_organization_rut` | `regexp_like(rut,'^[0-9]{7,8}-[0-9K]$')` |
| `organizations` | `ck_organization_status` | `(status in ('active','suspended'))` |
| `outbox` | `ck_outbox_status` | `(status in ('pending','delivered','failed'))` |
| `repactations` | `ck_repactation_amount` | `(monthly_amount > 0)` |
| `repactations` | `ck_repactation_currency` | `(currency in ('CLP','UF'))` |
| `repactations` | `ck_repactation_interest_rate` | `((interest_rate is null) or (interest_rate > 0))` |
| `repactations` | `ck_repactation_months` | `(months between 1 and 24)` |

</details>

---

## `tb_payments` — los cobros

La base de **ms-payments**. Cada intento de pago y su historia. Un pago nombra la deuda y las cuotas por su id, pero no hay llave foránea: viven en `tb_debt`, que es otra base, y quien los valida es ms-debt.

```mermaid
erDiagram
    payments ||--o| debt_notifications : "avisa con"
    payments ||--o{ payment_events : "deja rastro en"
    debt_notifications {
        bigint id PK
        bigint payment_id FK, UK
        varchar(10) status "pending, delivered o failed"
        smallint attempts
        datetime next_attempt_at "cuándo se reintenta"
        datetime delivered_at "opcional"
        varchar(300) last_error "opcional"
    }
    payment_events {
        bigint id PK
        bigint payment_id FK
        varchar(12) type "created, authorized, paid, failed, expired, refunded o duplicated"
        varchar(10) source "portal, webhook o manual"
        boolean signature_ok "opcional · si la firma del aviso era válida"
        json gateway_payload "opcional · lo que respondió la pasarela"
        datetime occurred_at
    }
    payments {
        bigint id PK
        bigint debt_id "la deuda (debts.id, en tb_debt)"
        bigint installment_id "opcional · la cuota, si es una sola"
        varchar(2000) installment_ids "opcional · las cuotas que cubre"
        varchar(12) debtor_rut
        varchar(12) creditor_rut
        decimal(18_2) amount "en la moneda de la deuda"
        decimal(18_2) interest_amount "opcional · la mora cobrada"
        decimal(18_2) discount_amount "opcional · lo condonado"
        varchar(3) currency "CLP o UF"
        bigint amount_clp "opcional · los pesos que cobra la pasarela"
        decimal(12_2) uf_value "opcional · la UF del día, si la deuda es en UF"
        varchar(20) gateway "mercadopago, khipu o webpay · no se repite junto con gateway_txn_id"
        varchar(80) gateway_txn_id "opcional · el id del cobro en la pasarela · no se repite junto con gateway"
        varchar(12) status "created, authorized, paid, failed, expired, refunded o duplicated"
        datetime created_at
        datetime redirected_at "opcional · cuándo se lo llevó a Webpay"
        datetime paid_at "opcional"
    }
    uf_values {
        date day PK
        decimal(12_2) value
        varchar(40) source "de dónde se trajo"
        datetime fetched_at
    }
```

| Tabla | Qué guarda |
| --- | --- |
| `debt_notifications` | El aviso pendiente hacia ms-debt por cada pago confirmado, con sus reintentos. |
| `payment_events` | El libro de cada pago: solo se agrega. Guarda lo que respondió la pasarela, tal como llegó. |
| `payments` | Cada intento de pago en una pasarela. Se crea al abrir el cobro, no al confirmarlo. |
| `uf_values` | El valor de la UF de cada día. |

| Vista | Qué calcula |
| --- | --- |
| `v_collection` | Por acreedor y mes: cuántos pagos se confirmaron y cuánto se recaudó en pesos. |

<details>
<summary>Las 12 reglas que exige la base (<code>CHECK</code>)</summary>

Lo que la aplicación valida, la base lo vuelve a exigir: aunque alguien escriba directo en ella, no puede dejar un dato que rompa estas reglas.

| Tabla | Regla | Qué exige |
| --- | --- | --- |
| `debt_notifications` | `ck_debt_notification_status` | `(status in ('pending','delivered','failed'))` |
| `payment_events` | `ck_payment_event_source` | `(source in ('portal','webhook','manual'))` |
| `payment_events` | `ck_payment_event_type` | `(type in ('created','authorized','paid','failed','expired','refunded','duplicated'))` |
| `payments` | `ck_payment_amount` | `(amount > 0)` |
| `payments` | `ck_payment_currency` | `(currency in ('CLP','UF'))` |
| `payments` | `ck_payment_discount` | `((discount_amount is null) or (discount_amount > 0))` |
| `payments` | `ck_payment_gateway` | `(gateway in ('mercadopago','khipu','webpay'))` |
| `payments` | `ck_payment_interest` | `((interest_amount is null) or ((interest_amount >= 0) and (interest_amount <= amount)))` |
| `payments` | `ck_payment_paid` | `(((status = 'paid') and (paid_at is not null)) or ((status <> 'paid') and (paid_at is null)))` |
| `payments` | `ck_payment_status` | `(status in ('created','authorized','paid','failed','expired','refunded','duplicated'))` |
| `payments` | `ck_payment_uf` | `((currency <> 'UF') or (status <> 'paid') or ((uf_value is not null) and (amount_clp is not null)))` |
| `uf_values` | `ck_uf_value` | `(value > 0)` |

</details>

---

## Cómo se unen las tres bases

Sin llaves foráneas entre ellas, lo que las une son datos que significan lo mismo en todas: el **RUT** (de una empresa o de un deudor) y el **id de la deuda**. Las líneas punteadas son esas referencias: las mantiene el código, no la base.

```mermaid
flowchart LR
    subgraph AUTH["tb_auth · ms-auth"]
        staff_users["staff_users"]
        access_codes["access_codes"]
        magic_links["magic_links"]
        sessions["sessions"]
        access_log["access_log"]
        auth_admin_log["admin_log"]
    end
    subgraph DEBT["tb_debt · ms-debt"]
        organizations["organizations"]
        debtors["debtors"]
        debts["debts"]
        installments["installments"]
        debt_events["debt_events"]
        debt_admin_log["admin_log"]
    end
    subgraph PAY["tb_payments · ms-payments"]
        payments["payments"]
    end
    staff_users -. "org_rut = rut" .-> organizations
    auth_admin_log -. "org_rut = rut" .-> organizations
    auth_admin_log -. "subject = email" .-> staff_users
    debt_admin_log -. "admin_email = email" .-> staff_users
    access_codes -. "debtor_rut = rut" .-> debtors
    magic_links -. "debtor_rut = rut" .-> debtors
    sessions -. "rut = rut, si es de un deudor" .-> debtors
    access_log -. "debtor_rut = rut" .-> debtors
    payments -. "debt_id = id" .-> debts
    payments -. "installment_ids" .-> installments
    payments -. "debtor_rut = rut" .-> debtors
    payments -. "creditor_rut = rut" .-> organizations
    debt_events -. "detail.pagoId = id" .-> payments
```

| Desde | Hacia | Con qué | Quién la mantiene |
| --- | --- | --- | --- |
| `tb_auth.staff_users.org_rut` | `tb_debt.organizations.rut` | El RUT de la empresa | ms-auth lo pone en el JWT, y ms-debt busca la empresa por ese RUT |
| `tb_auth.access_codes.debtor_rut` y los demás `debtor_rut` | `tb_debt.debtors.rut` | El RUT del deudor | ms-debt pide el código para ese RUT; ms-auth le pregunta a ms-debt el correo registrado |
| `tb_payments.payments.debt_id` e `installment_ids` | `tb_debt.debts.id` e `installments.id` | Los id de la deuda y de sus cuotas | ms-payments se los pide a ms-debt al abrir el cobro, junto con el monto |
| `tb_payments.payments.debtor_rut` y `creditor_rut` | `tb_debt.debtors.rut` y `organizations.rut` | El RUT de quien paga y de a quién | Vienen en la misma respuesta de ms-debt |
| `tb_debt.debt_events.detail` | `tb_payments.payments.id` | El id del pago aplicado | Lo trae el aviso `pago.confirmado` |
| `admin_log.admin_email`, en las dos bases | `tb_auth.staff_users.email` | El correo del admin que hizo algo | Sale del JWT de su sesión, no del cuerpo de la petición |
| `tb_auth.admin_log.org_rut` | `tb_debt.organizations.rut` | El RUT de la empresa de la persona | ms-auth le pregunta a ms-debt si esa empresa existe antes de darle una persona |

Cómo viaja cada uno de esos datos está en los [diagramas de secuencia](secuencia.md).
