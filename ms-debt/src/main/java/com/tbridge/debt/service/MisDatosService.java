package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.dto.request.MisDatosRequest;
import com.tbridge.debt.dto.response.MisDatosResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lo que DataBridge sabe del deudor, y de parte de quien lo sabe.
 *
 * <p>Es la respuesta a "¿y ustedes como tienen mis datos?": los trajo el
 * acreedor en su cartera. El contacto puede corregirse desde la sesion del
 * deudor; un correo nuevo requiere verificacion antes de guardarse.
 */
@Service
@Transactional(readOnly = true)
public class MisDatosService {

    private final DebtService debts;
    private final DebtRepository deudas;
    private final DebtorRepository debtors;
    private final int diasAntes;

    public MisDatosService(DebtService debts, DebtRepository deudas, DebtorRepository debtors,
                           @Value("${app.recordatorios.dias-antes:3}") int diasAntes) {
        this.debts = debts;
        this.deudas = deudas;
        this.debtors = debtors;
        this.diasAntes = diasAntes;
    }

    public MisDatosResponse ver(JwtPrincipal user) {
        return respuesta(deudor(user));
    }

    @Transactional
    public MisDatosResponse cambiar(JwtPrincipal user, MisDatosRequest pedido) {
        Debtor deudor = deudor(user);
        deudor.setReminders(pedido.recordatorios());
        deudor.setUpdatedAt(Instant.now());
        debtors.save(deudor);
        return respuesta(deudor);
    }

    private Debtor deudor(JwtPrincipal user) {
        if (user == null || !user.isDebtor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Estos datos son del deudor");
        }
        return debts.deudorDe(user);
    }

    private MisDatosResponse respuesta(Debtor deudor) {
        //  Un acreedor por fila, aunque le deba varias cosas; y quien cobra
        //  por su cuenta, si es una agencia y no el acreedor mismo.
        Map<Long, MisDatosResponse.Acreedor> porAcreedor = new LinkedHashMap<>();
        for (Debt deuda : deudas.findByDebtorOrderByUpdatedAtDesc(deudor)) {
            Organization acreedor = deuda.getCreditor();
            Organization cobra = deuda.getLastBatch().getSender();
            String agencia = cobra.getId().equals(acreedor.getId()) ? null : cobra.getTradeName();
            porAcreedor.merge(acreedor.getId(),
                    new MisDatosResponse.Acreedor(acreedor.getTradeName(), acreedor.getRut(), agencia, 1),
                    (antes, esta) -> new MisDatosResponse.Acreedor(antes.nombre(), antes.rut(),
                            antes.cobraPorSuCuenta() != null ? antes.cobraPorSuCuenta() : esta.cobraPorSuCuenta(),
                            antes.deudas() + 1));
        }
        return new MisDatosResponse(deudor.getFullName(), deudor.getRut(), deudor.getKind(),
                deudor.getEmail() == null ? null : AccesoService.enmascarar(deudor.getEmail()),
                enmascararTelefono(deudor.getPhone()), deudor.isReminders(), diasAntes,
                new ArrayList<>(porAcreedor.values()));
    }

    /** +56987654321 -> +569****4321: se reconoce sin quedar expuesto. */
    static String enmascararTelefono(String telefono) {
        if (telefono == null || telefono.isBlank()) {
            return null;
        }
        String limpio = telefono.replace(" ", "");
        if (limpio.length() <= 8) {
            return "*".repeat(Math.max(limpio.length() - 2, 0)) + limpio.substring(Math.max(limpio.length() - 2, 0));
        }
        return limpio.substring(0, 4) + "*".repeat(limpio.length() - 8) + limpio.substring(limpio.length() - 4);
    }
}
