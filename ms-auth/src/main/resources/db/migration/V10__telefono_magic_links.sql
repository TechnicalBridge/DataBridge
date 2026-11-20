-- Columna dedicada para el destinatario de SMS y compatibilidad con canales sin correo.
ALTER TABLE magic_links
    MODIFY COLUMN email VARCHAR(254) NULL COMMENT 'Correo destinatario para canal correo; nulo en canal SMS',
    ADD COLUMN phone VARCHAR(30) NULL COMMENT 'Telefono destinatario cuando el codigo se envia por SMS',
    ADD INDEX ix_magic_link_codigo_phone (phone, issued_at, id);
