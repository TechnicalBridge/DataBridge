-- =============================================================================
--  V13 — La agencia declara, renueva y termina su mandato en el portal (#234)
-- =============================================================================
--  Con la API apagada (#191), un mandato ya no tenia por donde nacer. Ahora la
--  agencia lo declara en su portal (declared_by guarda quien), y despues le
--  cambia la fecha de termino o lo termina: queda la ultima vez que se renovo y
--  cuando se termino, con quien lo hizo.
--
--  Terminado o vencido, lo ya cargado se sigue cobrando: solo deja de poder
--  cargarse cartera y crearse campanas para ese acreedor.
--
--  Solo agrega columnas vacias: no cambia ni borra filas.
-- =============================================================================

ALTER TABLE mandates
    ADD COLUMN renewed_at DATETIME(6)  NULL AFTER created_at,
    ADD COLUMN renewed_by VARCHAR(160) NULL AFTER renewed_at,
    ADD COLUMN ended_at   DATETIME(6)  NULL AFTER renewed_by,
    ADD COLUMN ended_by   VARCHAR(160) NULL AFTER ended_at;

--  Solo un mandato terminado tiene fecha de termino.
ALTER TABLE mandates ADD CONSTRAINT ck_mandate_terminado CHECK (ended_at IS NULL OR status = 'ended');
