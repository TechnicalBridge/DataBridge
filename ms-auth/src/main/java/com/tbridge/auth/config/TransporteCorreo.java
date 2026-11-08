package com.tbridge.auth.config;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** Selección única, compartida por la configuración SMTP y Brevo. */
final class TransporteCorreo {
    private TransporteCorreo() { }

    static String seleccionar(Environment env) {
        String modo = env.getProperty("app.mail.transport", "").strip();
        if (!modo.isEmpty()) {
            if (!java.util.Set.of("smtp", "brevo", "mailpit").contains(modo)) {
                throw new IllegalArgumentException("MAIL_TRANSPORT debe ser smtp, brevo o mailpit");
            }
            return modo;
        }
        if (StringUtils.hasText(env.getProperty("spring.mail.host"))) return "smtp";
        if (StringUtils.hasText(env.getProperty("app.mail.brevo.api-key"))) return "brevo";
        if (StringUtils.hasText(env.getProperty("app.mail.buzon-de-prueba"))) return "mailpit";
        return "none";
    }
}
