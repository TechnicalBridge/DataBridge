-- =============================================================================
--  tb_auth · V11 — el admin cierra a distancia la sesion de una persona (#439)
-- =============================================================================
--
--  Desde el panel, el admin de DataBridge ve quien del personal tiene una sesion
--  abierta y puede cerrarla: se revoca su familia de llaves de renovacion, como
--  cuando se detecta una llave robada. Cada cierre queda en la bitacora del
--  panel. Va despues de la V10 de #486 (correcciones del panel) y conserva su
--  accion, persona_corregida. Las filas que ya estan no cambian.
-- =============================================================================

ALTER TABLE admin_log DROP CHECK ck_admin_log_action;

ALTER TABLE admin_log ADD CONSTRAINT ck_admin_log_action CHECK (
    action IN ('persona_creada', 'admin_creado', 'persona_deshabilitada', 'persona_habilitada',
               'persona_corregida', 'sesion_cerrada')
);
