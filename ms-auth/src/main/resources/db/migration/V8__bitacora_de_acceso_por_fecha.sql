-- =============================================================================
--  tb_auth · V8 — la bitacora de acceso, por fecha (#141)
-- =============================================================================
--
--  El panel de administracion lee access_log por fecha, lo mas reciente
--  primero, y la filtra por resultado. Los indices de V1 empiezan por el RUT y
--  por la huella de la IP: sin estos, cada pagina recorreria la tabla entera,
--  que crece con cada intento de entrar.
--
--  Solo agrega indices: no cambia ni borra una fila.
-- =============================================================================

CREATE INDEX ix_access_log_cuando ON access_log (occurred_at);
CREATE INDEX ix_access_log_resultado ON access_log (outcome, occurred_at);
