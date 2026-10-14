-- =============================================================================
--  V8 — Acciones de suspender y reactivar empresa en la bitacora
-- =============================================================================
--  Permite registrar cuando un admin de DataBridge suspende o reactiva
--  a una empresa usuaria.
-- =============================================================================

ALTER TABLE admin_log DROP CHECK ck_admin_log_action;
ALTER TABLE admin_log ADD CONSTRAINT ck_admin_log_action CHECK (
    action IN ('empresa_creada', 'empresa_suspendida', 'empresa_reactivada')
);
