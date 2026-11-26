-- =============================================================================
--  tb_debt · V15 — el admin maneja las suscripciones de avisos (#438)
-- =============================================================================
--
--  Desde el panel, el admin de DataBridge ve las suscripciones de cada empresa
--  y puede activarlas, desactivarlas, cambiarles el secreto y probarlas, sin
--  SQL. Cada una de esas acciones queda en la bitacora del panel. Las filas que
--  ya estan no cambian.
-- =============================================================================

ALTER TABLE admin_log DROP CHECK ck_admin_log_action;

ALTER TABLE admin_log ADD CONSTRAINT ck_admin_log_action CHECK (
    action IN ('empresa_creada', 'aviso_reintentado', 'empresa_suspendida', 'empresa_reactivada',
               'deudor_consultado', 'suscripcion_activada', 'suscripcion_desactivada',
               'suscripcion_secreto_cambiado', 'suscripcion_probada')
);
