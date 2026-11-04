-- =============================================================================
--  V7 — el deudor cancela el intento desde el portal
-- =============================================================================
--  Cuando el deudor cierra la ventana de la pasarela y pide reiniciar, el
--  intento anterior queda vencido (`expired`) despues de preguntarle a la
--  pasarela si alcanzo a pagarse. La historia guarda un evento propio,
--  `cancelled`, con el motivo, la pasarela, la transaccion y quien lo pidio:
--  no es lo mismo que un rechazo de la pasarela (`failed`) ni que un cobro
--  que simplemente dejo pasar el plazo (`expired`).
-- =============================================================================

ALTER TABLE payment_events DROP CHECK ck_payment_event_type;
ALTER TABLE payment_events ADD CONSTRAINT ck_payment_event_type CHECK (type IN (
    'created', 'authorized', 'paid', 'failed', 'expired', 'refunded', 'duplicated', 'cancelled'
));
