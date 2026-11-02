-- =============================================================================
--  V12 — Suspender y reactivar una empresa desde el panel (#152)
-- =============================================================================
--  El admin de DataBridge le corta todo a una empresa usuaria (organizations.status
--  pasa a 'suspended', un estado que ya existia) y despues se lo devuelve. Las dos
--  cosas quedan en la bitacora del panel con quien las hizo, sobre esa empresa.
--
--  Solo amplia los valores que acepta admin_log.action: no cambia ni borra filas.
-- =============================================================================

ALTER TABLE admin_log DROP CHECK ck_admin_log_action;
ALTER TABLE admin_log ADD CONSTRAINT ck_admin_log_action CHECK (
    action IN ('empresa_creada', 'aviso_reintentado', 'empresa_suspendida', 'empresa_reactivada')
);
