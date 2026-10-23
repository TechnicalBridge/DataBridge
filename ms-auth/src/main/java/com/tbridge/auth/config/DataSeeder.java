package com.tbridge.auth.config;

import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.StaffUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * El personal de demostracion: quien entra al portal B2B.
 *
 * <p>La empresa de ejemplo cobra lo suyo, sin agencia. Su RUT coincide con
 * el de la demo por campanas de ms-debt. Solo se crea con DEMO_DATOS=true.
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
    private final boolean habilitada;

    public DataSeeder(StaffUserRepository personal, @Value("${app.demo.datos:false}") boolean habilitada) {
        this.personal = personal;
        this.habilitada = habilitada;
    }

    @Override
    public void run(String... args) {
        if (!habilitada || personal.existsByRole(StaffUser.Role.usuario)
                || personal.findByEmailIgnoreCase("camila.reyes@example.com").isPresent()) {
            return;
        }
        StaffUser camila = new StaffUser();
        camila.setOrgRut("76000000-0");
        camila.setEmail("camila.reyes@example.com");
        camila.setFullName("Camila Reyes");
        camila.setRole(StaffUser.Role.usuario);
        personal.save(camila);
        log.info("Personal de demostracion de Servicios Horizonte: entra con enlace al buzon de prueba");
    }
}
