package com.tbridge.auth.config;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Set;

public final class TransporteCorreo {
    private TransporteCorreo() {
    }

    public static String seleccionar(Environment env) {
        String modo = env.getProperty("app.mail.transport", "").strip();
        if (!modo.isEmpty()) {
            if (!Set.of("resend", "smtp", "brevo", "mailpit").contains(modo)) {
                throw new IllegalArgumentException("MAIL_TRANSPORT debe ser resend, smtp, brevo o mailpit");
            }
            return modo;
        }
        if (tiene(env, "app.mail.brevo.api-key")) return "brevo";
        if (tiene(env, "spring.mail.host")) return "smtp";
        if (tiene(env, "app.mail.buzon-de-prueba")) return "mailpit";
        return "none";
    }

    static boolean tiene(Environment env, String propiedad) {
        return StringUtils.hasText(env.getProperty(propiedad));
    }
}
