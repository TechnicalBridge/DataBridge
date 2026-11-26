ALTER TABLE admin_log ADD COLUMN previous_values TEXT NULL;
ALTER TABLE admin_log ADD COLUMN new_values TEXT NULL;
ALTER TABLE admin_log DROP CHECK ck_admin_log_action;
ALTER TABLE admin_log ADD CONSTRAINT ck_admin_log_action CHECK (
    action IN ('persona_creada', 'admin_creado', 'persona_deshabilitada', 'persona_habilitada',
               'persona_corregida')
);
