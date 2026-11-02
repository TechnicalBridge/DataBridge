-- =============================================================================
--  V11 — Reintentar un aviso desde el panel (#139)
-- =============================================================================
--  Un aviso que no se pudo entregar despues de los seis intentos queda en la
--  bandeja como 'failed', esperando un reenvio a mano. Desde el panel, el admin
--  de DataBridge lo vuelve a poner en cola, y eso queda en la bitacora del
--  panel con quien lo hizo, sobre la empresa a la que iba el aviso.
--
--  Solo amplia los valores que acepta admin_log.action: no cambia ni borra filas.
-- =============================================================================

ALTER TABLE admin_log DROP CHECK ck_admin_log_action;
ALTER TABLE admin_log ADD CONSTRAINT ck_admin_log_action CHECK (
    action IN ('empresa_creada', 'aviso_reintentado')
);
