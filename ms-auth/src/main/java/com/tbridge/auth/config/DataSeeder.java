package com.tbridge.auth.config;

import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.StaffUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * El personal de demostracion: quien entra al portal B2B.
 *
 * <p>En la cadena Patrimonio -> APOFYX -> DataBridge, quien opera la cartera
 * en DataBridge es APOFYX, la agencia. Patrimonio no entra aqui: no sabe ni
 * necesita saber que DataBridge existe.
 *
 * <p>Solo corre si no hay ninguna persona de una empresa, asi que no pisa a
 * nadie. El personal entra con enlace al correo; no hay contrasenas que sembrar.
 * El admin de DataBridge no viene de aqui: lo crea {@link PrimerAdmin}.
 */
@Component
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final StaffUserRepository personal;

    public DataSeeder(StaffUserRepository personal) {
        this.personal = personal;
    }

    @Override
    public void run(String... args) {
        if (personal.existsByRole(StaffUser.Role.usuario)) {
            return;
        }
        StaffUser camila = new StaffUser();
        camila.setOrgRut("77305118-6");
        camila.setEmail("camila.reyes@apofyx.cl");
        camila.setFullName("Camila Reyes");
        camila.setRole(StaffUser.Role.usuario);
        personal.save(camila);
        log.info("Personal de demostracion: {} (APOFYX), entra con enlace al correo", camila.getEmail());
    }
}
