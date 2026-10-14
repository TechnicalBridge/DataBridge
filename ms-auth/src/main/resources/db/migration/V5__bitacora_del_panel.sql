-- =============================================================================
--  tb_auth · V5 — la bitacora del panel de administracion
-- =============================================================================
--
--  Lo que un admin de DataBridge hace con las personas desde su panel queda
--  escrito: que hizo, a quien, quien fue y cuando. Dar de alta a una persona de
--  una empresa, dar de alta a otro admin, y quitarle o devolverle el acceso a
--  alguien. La empresa misma se da de alta en ms-debt, y su bitacora la anota:
--  cada servicio escribe lo suyo.
--
--  No es access_log: ese cuenta quien entro y por donde. Este cuenta quien
--  cambio a quien.
--
--  admin_email  Quien lo hizo.
--  org_rut      La empresa de la persona. Vacio si la persona es un admin.
--  subject      A quien: el correo de la persona.
--
--  Solo se agrega: nada actualiza ni borra una fila.
-- =============================================================================

CREATE TABLE admin_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    admin_email  VARCHAR(254) NOT NULL,
    action       VARCHAR(40)  NOT NULL,
    org_rut      VARCHAR(12)      NULL,
    subject      VARCHAR(254)     NULL,
    occurred_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_admin_log PRIMARY KEY (id),
    CONSTRAINT ck_admin_log_action CHECK (
        action IN ('persona_creada', 'admin_creado', 'persona_deshabilitada', 'persona_habilitada')
    ),

    INDEX ix_admin_log_empresa (org_rut, occurred_at)
) ENGINE=InnoDB;
