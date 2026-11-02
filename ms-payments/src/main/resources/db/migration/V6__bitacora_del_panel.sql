-- =============================================================================
--  tb_payments · V6 — la bitacora del panel de administracion (#139)
-- =============================================================================
--
--  Lo que el admin de DataBridge hace desde el panel con los pagos queda
--  escrito: que hizo, sobre que, quien fue y cuando. Por ahora, dos cosas:
--  volver a poner en cola un aviso de pago a ms-debt que no se entrego, y
--  cargar a mano la UF de un dia cuando el Banco Central no la entrego.
--  Es la misma idea que admin_log en tb_auth y en tb_debt: cada servicio
--  escribe lo suyo.
--
--  admin_email  Quien lo hizo: el correo del admin, que vive en tb_auth.
--  subject      Sobre que: el pago del aviso, o el dia y el valor de la UF.
--
--  Solo se agrega: nada actualiza ni borra una fila.
-- =============================================================================

CREATE TABLE admin_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    admin_email  VARCHAR(254) NOT NULL,
    action       VARCHAR(40)  NOT NULL,
    subject      VARCHAR(254)     NULL,
    occurred_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_admin_log PRIMARY KEY (id),
    CONSTRAINT ck_admin_log_action CHECK (action IN ('aviso_reintentado', 'uf_cargada')),

    INDEX ix_admin_log_cuando (occurred_at)
) ENGINE=InnoDB;
