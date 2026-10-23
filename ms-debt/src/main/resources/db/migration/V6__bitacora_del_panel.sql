-- =============================================================================
--  V6 — La bitacora del panel de administracion
-- =============================================================================
--  Lo que un admin de DataBridge hace desde su panel queda escrito: que hizo,
--  sobre que empresa, quien fue y cuando. Aqui, las empresas que da de alta.
--  Lo que hace con las personas queda en la bitacora de ms-auth, que es donde
--  viven: cada servicio anota lo suyo.
--
--  admin_email  Quien lo hizo. Es el correo y no un id: el admin vive en
--               tb_auth, que es otra base, y un correo se entiende al leerlo.
--  org_rut      La empresa sobre la que se hizo.
--  subject      A que o a quien: el nombre de la empresa creada.
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
    CONSTRAINT ck_admin_log_action CHECK (action IN ('empresa_creada')),

    INDEX ix_admin_log_empresa (org_rut, occurred_at)
) ENGINE=InnoDB;
