-- Corte unico de la era de firma: las cookies anteriores no pueden renovar
-- automaticamente a ES256. Flyway ejecuta esto una vez, antes de aceptar trafico.
-- Detener todas las replicas antiguas de auth durante la transicion.
-- Conserva usuarios, familias, hashes e historial; no se vuelve a abrir ninguna
-- sesion que ya estuviera revocada. No repetir en cada arranque o rotacion de llave.
UPDATE sessions
   SET revoked_at = UTC_TIMESTAMP(6)
 WHERE revoked_at IS NULL;
