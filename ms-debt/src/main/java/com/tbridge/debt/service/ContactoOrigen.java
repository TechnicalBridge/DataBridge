package com.tbridge.debt.service;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only explicitly configured gateway/proxy peers can supply forwarding headers. */
@Component
public class ContactoOrigen {
    private final Pattern confiables;
    public ContactoOrigen(@Value("${app.contact-trusted-proxies:}") String trusted) {
        this.confiables=Pattern.compile(trusted);
    }
    public String de(HttpServletRequest request) {
        return resolver(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
    }
    /** La regla sola, sin la peticion alrededor: es lo que prueban las pruebas. */
    String resolver(String par, String reenviadoPor) {
        if (par == null) {
            return "desconocido";
        }
        if (!confiables.matcher(par).matches()) {
            return par;
        }
        if (reenviadoPor == null || reenviadoPor.isBlank()) {
            return par;
        }
        String[] partes = reenviadoPor.split(",");
        for (int i = partes.length - 1; i >= 0; i--) {
            String ip = partes[i].trim();
            if (!ip.isEmpty() && !confiables.matcher(ip).matches()) {
                return ip;
            }
        }
        for (int i = 0; i < partes.length; i++) {
            String ip = partes[i].trim();
            if (!ip.isEmpty()) {
                return ip;
            }
        }
        return par;
    }
}
