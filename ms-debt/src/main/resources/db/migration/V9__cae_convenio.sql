-- =============================================================================
--  V9 — Carga Anual Equivalente (CAE) del convenio
-- =============================================================================
--  Ley 20.555 (SERNAC Financiero) y D.S. 43 de 2012 exigen informar la CAE y
--  el costo total antes de que el deudor acepte el convenio.
--  La CAE queda guardada con el convenio aceptado para que lo informado no cambie.
-- =============================================================================

ALTER TABLE repactations ADD COLUMN cae DECIMAL(6,2) NULL AFTER principal;
ALTER TABLE repactations ADD CONSTRAINT ck_repactation_cae CHECK (cae IS NULL OR cae >= 0);
