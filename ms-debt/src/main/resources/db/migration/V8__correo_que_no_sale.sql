-- =============================================================================
--  V8 — El correo que no sale
-- =============================================================================
--  Hasta aqui, si el servidor de correo rechazaba el codigo de un deudor, ms-auth
--  respondia igual que si hubiera salido: ms-debt lo anotaba como contacto, lo
--  contaba para el limite de la ley y la empresa veia "Ultimo correo el...".
--  Ahora ms-auth dice que no salio, y ese intento no es un contacto: queda
--  anotado en el deudor, que es de quien es la direccion.
--
--  debtors.email_failed_at   La ultima vez que un correo a este deudor no
--                            salio. Vacio si el ultimo salio.
--  debtors.email_failure     Por que, en palabras para la empresa y con la
--                            direccion tapada.
--  debtors.email_rejections  Cuantas veces seguidas el servidor rechazo esta
--                            direccion. A las tres es un "correo con
--                            problemas": ya no se le escribe solo, hasta que
--                            una cartera traiga otro correo. Una falla del
--                            servidor de correo (credenciales, cupo, no
--                            responde) no cuenta: no es culpa de la direccion.
--
--  Un correo que sale, o un correo nuevo para el deudor, deja las tres en cero.
-- =============================================================================

ALTER TABLE debtors
    ADD COLUMN email_failed_at  DATETIME(6)       NULL AFTER email,
    ADD COLUMN email_failure    VARCHAR(300)      NULL AFTER email_failed_at,
    ADD COLUMN email_rejections SMALLINT UNSIGNED NOT NULL DEFAULT 0 AFTER email_failure;

ALTER TABLE debtors
    ADD CONSTRAINT ck_debtor_email_failure CHECK ((email_failed_at IS NULL) = (email_failure IS NULL)),
    ADD CONSTRAINT ck_debtor_email_rejections CHECK (email_rejections = 0 OR email_failed_at IS NOT NULL);
