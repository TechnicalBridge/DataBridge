-- Los accesos solicitados desde el portal comparten codigo y enlace opcional.
-- Los enlaces anteriores conservan su comportamiento: sin codigo y habilitados.
ALTER TABLE magic_links
    ADD COLUMN code_hash CHAR(64) NULL COMMENT 'Huella del codigo solicitado; nunca el codigo en claro',
    ADD COLUMN attempts SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Intentos fallidos del codigo',
    ADD COLUMN max_attempts SMALLINT UNSIGNED NOT NULL DEFAULT 5 COMMENT 'Limite compartido por codigo y enlace',
    ADD COLUMN link_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'El enlace se ofrece solo al pedir respaldo',
    ADD CONSTRAINT ck_magic_link_intentos CHECK (attempts <= max_attempts),
    ADD INDEX ix_magic_link_codigo_rut (debtor_rut, issued_at, id),
    ADD INDEX ix_magic_link_codigo_correo (email, issued_at, id);
