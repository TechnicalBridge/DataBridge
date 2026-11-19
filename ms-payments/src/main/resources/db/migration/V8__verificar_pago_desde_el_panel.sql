-- =============================================================================
--  tb_payments · V8 — el admin verifica un pago abierto con su pasarela (#435)
-- =============================================================================
--
--  Desde la pantalla Pagos del panel, el admin puede volver a preguntarle a la
--  pasarela por un pago que sigue abierto, lo mismo que hace la consulta
--  periodica. Si la pasarela ya cobro, se confirma por el camino de siempre.
--  Cada verificacion queda en la bitacora del panel como pago_verificado. Las
--  filas que ya estan no cambian.
-- =============================================================================

ALTER TABLE admin_log DROP CHECK ck_admin_log_action;

ALTER TABLE admin_log
    ADD CONSTRAINT ck_admin_log_action
        CHECK (action IN ('aviso_reintentado', 'uf_cargada', 'cobro_liberado', 'pago_verificado'));
