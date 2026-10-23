package com.tbridge.debt.service;

import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.ContactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Cuantas veces se le puede escribir a un deudor.
 *
 * <p>La Ley 19.496 (art. 37, con los cambios de la Ley 21.320) deja hacer por
 * correo, SMS o mensajeria a lo mas <b>dos gestiones de cobranza por semana,
 * separadas por al menos dos dias</b>. Aca se cuenta todo lo que le llega al
 * deudor desde DataBridge, de todas sus deudas: la invitacion, los toques de la
 * campana, el recordatorio de una cuota y el codigo que reenvia la empresa.
 * Cada uno queda como un contacto de su deuda ({@code contacts}), que es
 * tambien el registro de gestiones que pide la ley.
 *
 * <p>Solo para probar, {@code CONTACTO_SIN_LIMITE=true} lo apaga: se le escribe
 * cuantas veces se pida, y cada correo se sigue anotando. Nunca en produccion.
 *
 * <p>No cuenta el codigo que pide el propio deudor para entrar: eso no es
 * cobranza, y lo emite ms-auth sin pasar por aca.
 */
@Service
public class LimiteDeContacto {

    /** Gestiones por semana, contando la de hoy. */
    static final int POR_SEMANA = 2;

    /** Dias que tienen que pasar entre una gestion y la siguiente. */
    static final int DIAS_ENTRE = 2;

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private static final Logger log = LoggerFactory.getLogger(LimiteDeContacto.class);

    private final ContactRepository contactos;
    /** Solo para probar ({@code CONTACTO_SIN_LIMITE}): se le escribe cuantas veces se pida. */
    private final boolean apagado;

    public LimiteDeContacto(ContactRepository contactos) {
        this(contactos, false);
    }

    @Autowired
    public LimiteDeContacto(ContactRepository contactos,
                            @Value("${app.contacto.sin-limite:false}") boolean apagado) {
        this.contactos = contactos;
        this.apagado = apagado;
        if (apagado) {
            log.warn("LIMITE DE LA LEY APAGADO (CONTACTO_SIN_LIMITE=true): se le escribe a un deudor cuantas veces "
                    + "se pida, sin el tope de dos por semana. Es solo para probar: nunca en produccion");
        }
    }

    /** Si el limite esta apagado para probar ({@code CONTACTO_SIN_LIMITE}). */
    public boolean apagado() {
        return apagado;
    }

    /** Si en este momento se le puede escribir al deudor. */
    public boolean permite(Debtor deudor, Instant ahora) {
        if (apagado) {
            return true;
        }
        LocalDate hoy = LocalDate.ofInstant(ahora, CHILE);
        //  La semana son los ultimos siete dias, hoy incluido.
        Instant desde = hoy.minusDays(6).atStartOfDay(CHILE).toInstant();
        List<LocalDate> dias = contactos.cuandoSeLeEscribio(deudor, desde)
                .stream()
                .map(cuando -> LocalDate.ofInstant(cuando, CHILE))
                .filter(dia -> !dia.isAfter(hoy))
                .toList();
        if (dias.size() >= POR_SEMANA) {
            return false;
        }
        LocalDate ultimoPosible = hoy.minusDays(DIAS_ENTRE);
        return dias.stream().noneMatch(dia -> dia.isAfter(ultimoPosible));
    }
}
