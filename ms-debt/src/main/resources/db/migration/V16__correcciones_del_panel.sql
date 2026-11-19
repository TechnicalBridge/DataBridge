ALTER TABLE admin_log ADD COLUMN previous_values TEXT NULL;
ALTER TABLE admin_log ADD COLUMN new_values TEXT NULL;
ALTER TABLE admin_log DROP CHECK ck_admin_log_action;
ALTER TABLE admin_log ADD CONSTRAINT ck_admin_log_action CHECK (
    action IN ('empresa_creada', 'aviso_reintentado', 'empresa_suspendida',
               'empresa_reactivada', 'deudor_consultado', 'suscripcion_activada',
               'suscripcion_desactivada', 'suscripcion_secreto_cambiado',
               'suscripcion_probada', 'empresa_corregida')
);
