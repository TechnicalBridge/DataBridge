package com.tbridge.auth.config;

import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.StaffUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * El primer admin de DataBridge.
 *
 * <p>Alguien tiene que poder entrar al panel de administracion antes de que
 * exista nadie que lo de de alta: sale de {@code ADMIN_CORREO}. Se crea una
 * sola vez, si todavia no hay ningun admin; los demas los da de alta un admin
 * desde el panel. Entra con un enlace a ese correo, sin contrasena.
 *
 * <p>Sin {@code ADMIN_CORREO} no hay admin y nadie entra al panel: es el valor
 * por omision a proposito.
 */
@Component
@Order(2)
public class PrimerAdmin implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PrimerAdmin.class);

    private final StaffUserRepository personal;
    private final String correo;

    public PrimerAdmin(StaffUserRepository personal, @Value("${app.admin-email:}") String correo) {
        this.personal = personal;
        this.correo = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(String... args) {
        if (personal.existsByRole(StaffUser.Role.admin)) {
            return;
        }
        if (correo.isEmpty()) {
            log.info("Sin ADMIN_CORREO: no hay admin de DataBridge, y nadie puede entrar al panel de administracion");
            return;
        }
        if (!correo.contains("@")) {
            log.error("ADMIN_CORREO no es un correo: no se crea el admin de DataBridge");
            return;
        }
        //  Un admin es solo de DataBridge: a una persona de una empresa no se le da ese rol.
        if (personal.findByEmailIgnoreCase(correo).isPresent()) {
            log.error("ADMIN_CORREO ({}) ya es de una persona de una empresa: no se le da el rol admin. Usa otro correo.",
                    correo);
            return;
        }
        StaffUser admin = new StaffUser();
        admin.setEmail(correo);
        admin.setFullName("Administrador de DataBridge");
        admin.setRole(StaffUser.Role.admin);
        personal.save(admin);
        log.info("Primer admin de DataBridge: {}, entra con enlace al correo", correo);
    }
}
