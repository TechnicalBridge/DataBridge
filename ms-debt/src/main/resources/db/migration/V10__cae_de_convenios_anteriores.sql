-- =============================================================================
--  V10 — La CAE de los convenios aceptados antes de V9
-- =============================================================================
--  V9 agrego la columna, pero los convenios que ya estaban quedaron sin CAE,
--  mientras que uno nuevo sin interes guarda 0.00. Se completan con la tasa
--  mensual por 12, que con cuotas de sistema frances es la CAE salvo el
--  redondeo de las cuotas (en los montos de siempre, a lo mas una centesima).
--  Sin tasa, 0.00. No toca los que ya tienen su CAE.
-- =============================================================================

UPDATE repactations
SET cae = ROUND(COALESCE(interest_rate, 0) * 12, 2)
WHERE cae IS NULL;
