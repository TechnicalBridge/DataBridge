-- =============================================================================
--  tb_payments · V7 — el admin libera un cobro trabado (#453)
-- =============================================================================
--
--  Un pago de Mercado Pago "en proceso" puede dejar la cuota bloqueada por
--  horas: DataBridge no vence un cobro mientras la pasarela lo tenga en curso.
--  Desde Operacion, el admin ahora puede liberar un cobro abierto hace mas de
--  una hora, y queda escrito en la bitacora del panel como cobro_liberado.
--  Las filas que ya estan no cambian.
-- =============================================================================

ALTER TABLE admin_log DROP CHECK ck_admin_log_action;

ALTER TABLE admin_log
    ADD CONSTRAINT ck_admin_log_action
        CHECK (action IN ('aviso_reintentado', 'uf_cargada', 'cobro_liberado'));
