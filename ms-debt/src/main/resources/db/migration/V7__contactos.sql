-- =============================================================================
--  V7 — Los contactos al deudor, en su propia tabla
-- =============================================================================
--  Cada vez que DataBridge le escribe a un deudor queda un contacto: la
--  invitacion, un toque de campana, el recordatorio de una cuota o el codigo
--  que reenvia la empresa. Son las gestiones de cobranza que la ley manda
--  registrar y limitar (Ley 19.496, art. 37: dos por semana, con dos dias
--  entre una y otra).
--
--  Hasta aqui eran eventos `code_sent` de debt_events, con lo que distingue a
--  un contacto de otro escondido: la campana y el numero de toque dentro del
--  JSON de `detail`, y el destino o una descripcion como texto en `reference`.
--  Para contar los toques de una deuda habia que abrir el JSON de cada evento.
--
--  contacts.kind            Que contacto es:
--                             invitation  el primer aviso, cuando la deuda entra
--                             campaign    un toque de su campana
--                             reminder    el recordatorio de una cuota por vencer
--                             resend      el codigo que reenvia la empresa a mano
--  contacts.channel         Por donde se le escribio. Hoy, solo por correo.
--  contacts.destination     A donde, con la direccion tapada
--                           (fe**********@correo.cl). Vacio en los contactos
--                           anteriores a esta migracion que no lo guardaron.
--  contacts.campaign_id     De que campana es el toque.
--  contacts.touch_number    Que toque es de esa campana para esa deuda: 1, 2...
--  contacts.installment_id  Que cuota se recordo.
--
--  El deudor no se repite aqui: es el de la deuda.
--
--  Dos cosas que antes cuidaba solo el codigo ahora las cuida la base: un
--  toque de una campana sale una sola vez por deuda, y una cuota se recuerda
--  una sola vez. Por eso installments.reminded_at deja de hacer falta: que la
--  cuota ya se aviso es que existe su contacto.
-- =============================================================================

CREATE TABLE contacts (
    id              BIGINT            NOT NULL AUTO_INCREMENT,
    debt_id         BIGINT            NOT NULL,
    kind            VARCHAR(12)       NOT NULL,
    channel         VARCHAR(10)       NOT NULL DEFAULT 'email',
    destination     VARCHAR(254)          NULL,
    campaign_id     BIGINT                NULL,
    touch_number    SMALLINT UNSIGNED     NULL,
    installment_id  BIGINT                NULL,
    occurred_at     DATETIME(6)       NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_contact PRIMARY KEY (id),
    CONSTRAINT uq_contact_touch UNIQUE (debt_id, campaign_id, touch_number),
    CONSTRAINT uq_contact_reminder UNIQUE (installment_id),

    CONSTRAINT fk_contact_debt FOREIGN KEY (debt_id)
        REFERENCES debts (id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_campaign FOREIGN KEY (campaign_id)
        REFERENCES campaigns (id),
    CONSTRAINT fk_contact_installment FOREIGN KEY (installment_id)
        REFERENCES installments (id) ON DELETE CASCADE,

    CONSTRAINT ck_contact_kind CHECK (kind IN ('invitation', 'campaign', 'reminder', 'resend')),
    CONSTRAINT ck_contact_channel CHECK (channel IN ('email')),
    --  La campana y el numero de toque van juntos, y solo en un toque de campana.
    CONSTRAINT ck_contact_campaign CHECK (
        (kind = 'campaign' AND campaign_id IS NOT NULL AND touch_number IS NOT NULL)
        OR (kind <> 'campaign' AND campaign_id IS NULL AND touch_number IS NULL)
    ),
    CONSTRAINT ck_contact_touch CHECK (touch_number IS NULL OR touch_number >= 1),
    --  La cuota va solo en un recordatorio, y siempre.
    CONSTRAINT ck_contact_reminder CHECK (
        (kind = 'reminder' AND installment_id IS NOT NULL)
        OR (kind <> 'reminder' AND installment_id IS NULL)
    ),

    INDEX ix_contact_debt (debt_id, occurred_at)
) ENGINE=InnoDB;


-- -----------------------------------------------------------------------------
--  Lo que ya existe pasa a la tabla nueva.
-- -----------------------------------------------------------------------------

--  Los eventos code_sent, menos los recordatorios, que van aparte.
--
--  Es un toque de campana el que nombra en su JSON una campana que existe. El
--  numero de toque no se toma del JSON: se cuenta en orden, que es como lo
--  calculaba el codigo, y asi no puede venir repetido. El CASE del JOIN lee el
--  JSON solo cuando trae un entero: comparar otra cosa con un id es un error
--  de MySQL, y dejaria la migracion a medias.
--
--  Lo demas es la invitacion si lo mando el sistema, y el reenvio si lo pidio
--  la empresa. Su referencia era el correo tapado. Un toque cuya campana ya no
--  existe queda como invitacion: no se pierde, y sigue contando para el limite.
INSERT INTO contacts (debt_id, kind, destination, campaign_id, touch_number, occurred_at)
SELECT e.debt_id,
       CASE WHEN c.id IS NOT NULL THEN 'campaign'
            WHEN e.actor = 'system' THEN 'invitation'
            ELSE 'resend' END,
       CASE WHEN c.id IS NULL AND e.reference LIKE '%@%' THEN e.reference END,
       c.id,
       CASE WHEN c.id IS NOT NULL
            THEN ROW_NUMBER() OVER (PARTITION BY e.debt_id, c.id ORDER BY e.occurred_at, e.id) END,
       e.occurred_at
  FROM debt_events e
  LEFT JOIN campaigns c
         ON c.id = CASE WHEN JSON_TYPE(JSON_EXTRACT(e.detail, '$.campana')) = 'INTEGER'
                        THEN CAST(JSON_EXTRACT(e.detail, '$.campana') AS SIGNED) END
 WHERE e.type = 'code_sent'
   AND (c.id IS NOT NULL OR e.reference IS NULL OR e.reference NOT LIKE 'recordatorio de la cuota%');

--  Los recordatorios salen de las cuotas avisadas, que es donde quedo anotado
--  cual cuota fue: uno por cuota. Su evento decia lo mismo, como texto.
INSERT INTO contacts (debt_id, kind, installment_id, occurred_at)
SELECT i.debt_id, 'reminder', i.id, i.reminded_at
  FROM installments i
 WHERE i.reminded_at IS NOT NULL;


-- -----------------------------------------------------------------------------
--  Y deja de estar donde estaba: un hecho, un solo lugar.
-- -----------------------------------------------------------------------------

DELETE FROM debt_events WHERE type = 'code_sent';

ALTER TABLE debt_events DROP CHECK ck_debt_event_type;
ALTER TABLE debt_events ADD CONSTRAINT ck_debt_event_type CHECK (type IN (
    'registered', 'updated', 'withdrawn', 'portal_entered',
    'repacted', 'payment_applied', 'settled', 'disputed'
));

ALTER TABLE installments DROP COLUMN reminded_at;
