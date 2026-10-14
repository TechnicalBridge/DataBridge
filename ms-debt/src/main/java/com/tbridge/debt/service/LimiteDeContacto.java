package com.tbridge.debt.service;

import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.ContactRepository;
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

    private final ContactRepository contactos;

    public LimiteDeContacto(ContactRepository contactos) {
        this.contactos = contactos;
    }

    /** Si en este momento se le puede escribir al deudor. */
    public boolean permite(Debtor deudor, Instant ahora) {
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
