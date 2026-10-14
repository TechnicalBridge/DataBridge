-- =============================================================================
--  tb_auth · V4 — dos roles: admin (DataBridge) y usuario (una empresa)
-- =============================================================================
--
--  El personal tenia dos roles por empresa, 'operator' y 'admin', que no
--  cambiaban nada: los dos podian lo mismo. Ahora los roles son dos y dicen
--  otra cosa (#137):
--
--    admin     es de DataBridge. Entra al panel de administracion, y es el
--              unico que da de alta empresas y personas.
--    usuario   es de una empresa usuaria (una agencia o un acreedor que cobra
--              directo). Entra al portal de su empresa y ve solo lo suyo.
--
--  El control lo tiene solo DataBridge, y eso queda en el esquema: un admin
--  nunca pertenece a una empresa, y una persona de una empresa nunca es admin.
--  No hay como guardar lo contrario.
-- =============================================================================

ALTER TABLE staff_users DROP CHECK ck_staff_user_role;

--  Todas las personas que ya existen son de una empresa: quedan como usuario.
UPDATE staff_users SET role = 'usuario';

ALTER TABLE staff_users
    --  El admin no tiene empresa.
    MODIFY org_rut VARCHAR(12)     NULL,
    MODIFY role    VARCHAR(10) NOT NULL DEFAULT 'usuario',
    ADD CONSTRAINT ck_staff_user_role CHECK (role IN ('usuario', 'admin')),
    ADD CONSTRAINT ck_staff_user_admin_sin_empresa CHECK (
        (role = 'admin'   AND org_rut IS NULL) OR
        (role = 'usuario' AND org_rut IS NOT NULL)
    );


--  La sesion del admin lleva su propio rol, distinto del de las empresas
--  (CREDITOR), para que no se le abra nada de lo que ya existia.
ALTER TABLE sessions DROP CHECK ck_session_role;
ALTER TABLE sessions DROP CHECK ck_session_identidad;
ALTER TABLE sessions
    ADD CONSTRAINT ck_session_role CHECK (role IN ('DEBTOR', 'CREDITOR', 'ADMIN')),
    ADD CONSTRAINT ck_session_identidad CHECK (
        (role = 'DEBTOR' AND rut IS NOT NULL) OR
        (role IN ('CREDITOR', 'ADMIN') AND email IS NOT NULL)
    );
