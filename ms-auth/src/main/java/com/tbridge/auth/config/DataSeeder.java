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
 * <p>En la cadena Patrimonio -> APOFYX -> DataBridge, quien opera la cartera
 * en DataBridge es APOFYX, la agencia. Patrimonio no entra aqui: no sabe ni
 * necesita saber que DataBridge existe.
 *
 * <p>Solo corre si app.demo.datos=true y no hay ninguna persona de una empresa,
 * asi que no pisa a nadie y fuera de la demo no nace ningun dato ficticio (#151).
 */
@Component
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final StaffUserRepository personal;
    private final boolean demoDatos;

    public DataSeeder(StaffUserRepository personal,
                      @Value("${app.demo.datos:false}") boolean demoDatos) {
        this.personal = personal;
        this.demoDatos = demoDatos;
    }

    @Override
    public void run(String... args) {
        if (!demoDatos || personal.existsByRole(StaffUser.Role.usuario)) {
            return;
        }
        StaffUser camila = new StaffUser();
        camila.setOrgRut("77305118-6");
        camila.setEmail("camila.reyes@apofyx.example.com");
        camila.setFullName("Camila Reyes");
        camila.setRole(StaffUser.Role.usuario);
        personal.save(camila);
        log.info("Personal de demostracion: {} (APOFYX), entra con enlace al correo", camila.getEmail());
    }
}
