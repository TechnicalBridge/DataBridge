-- =============================================================================
--  tb_debt · V14 — el admin busca a un deudor y abre su ficha (#434)
-- =============================================================================
--
--  Desde el panel, el admin de DataBridge puede buscar a un deudor en todas las
--  empresas y abrir su ficha, que muestra sus datos personales. Cada ficha que
--  se abre queda en la bitacora del panel como deudor_consultado, con el RUT
--  del deudor. Las filas que ya estan no cambian.
-- =============================================================================

ALTER TABLE admin_log DROP CHECK ck_admin_log_action;

ALTER TABLE admin_log ADD CONSTRAINT ck_admin_log_action CHECK (
    action IN ('empresa_creada', 'aviso_reintentado', 'empresa_suspendida', 'empresa_reactivada',
               'deudor_consultado')
);
