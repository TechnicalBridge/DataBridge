package com.tbridge.payments.service;

import com.tbridge.payments.client.BancoCentralClient;
import com.tbridge.payments.dto.response.UfCargaResponse;
import com.tbridge.payments.model.UfValue;
import com.tbridge.payments.repository.UfValueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Mantiene cargada la UF: al arrancar y todos los dias en la manana de Chile.
 *
 * <p>Pide desde una semana atras hasta cuarenta dias adelante, asi que una
 * caida de varios dias del Banco Central, o de este servicio, no deja huecos:
 * la siguiente carga los rellena. Un cobro en UF nunca usa el valor de otro
 * dia (ver {@link UfService}), y por eso importa tener el mes por adelantado.
 */
@Service
public class UfLoader {

    private static final Logger log = LoggerFactory.getLogger(UfLoader.class);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final BancoCentralClient bancoCentral;
    private final UfValueRepository valores;

    private final BigDecimal valorDefecto;

    public UfLoader(BancoCentralClient bancoCentral, UfValueRepository valores,
                    @org.springframework.beans.factory.annotation.Value("${app.uf.valor-defecto:38000.00}") BigDecimal valorDefecto) {
        this.bancoCentral = bancoCentral;
        this.valores = valores;
        this.valorDefecto = valorDefecto;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (!bancoCentral.configurado()) {
            log.warn("Sin BCENTRAL_USER/BCENTRAL_PASS: la UF no se carga sola. "
                    + "Cargala con POST /internal/uf o configura las credenciales del Banco Central.");
            LocalDate hoy = LocalDate.now(CHILE);
            if (valores.findById(hoy).isEmpty() && valorDefecto != null && valorDefecto.signum() > 0) {
                valores.save(new UfValue(hoy, valorDefecto, "fallback_local"));
                log.info("Ambiente sin credenciales del Banco Central: sembrando valor referencial de UF {} para el día {}",
                        valorDefecto, hoy);
            }
            return;
        }
        cargar();
    }

    @Scheduled(cron = "${app.uf.cron:0 30 9 * * *}", zone = "America/Santiago")
    public void todosLosDias() {
        if (bancoCentral.configurado()) {
            cargar();
        }
    }

    /** Baja el rango y guarda lo publicado. Nunca lanza: una falla se reintenta manana. */
    public UfCargaResponse cargar() {
        LocalDate hoy = LocalDate.now(CHILE);
        try {
            List<UfValue> publicados = bancoCentral.obtener(hoy.minusDays(7), hoy.plusDays(40));
            valores.saveAll(publicados);
            LocalDate hasta = publicados.stream().map(UfValue::getDay).max(LocalDate::compareTo).orElse(null);
            log.info("UF del Banco Central: {} dias cargados, hasta el {}", publicados.size(), hasta);
            if (valores.findById(hoy).isEmpty()) {
                log.warn("El Banco Central no trajo la UF de hoy ({}): los cobros en UF se detienen", hoy);
            }
            return UfCargaResponse.exito(publicados.size(), hasta);
        } catch (InterruptedException corte) {
            Thread.currentThread().interrupt();
            return UfCargaResponse.fallo("interrumpido");
        } catch (Exception fallo) {
            log.warn("No se pudo cargar la UF del Banco Central: {}", fallo.getMessage());
            return UfCargaResponse.fallo(fallo.getMessage());
        }
    }
}
