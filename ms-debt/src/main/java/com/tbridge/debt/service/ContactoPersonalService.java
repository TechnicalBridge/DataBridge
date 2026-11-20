package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.MisDatosResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.ContactUpdate;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.ContactUpdateRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.security.JwtPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Objects;

@Service
public class ContactoPersonalService {
    private final DebtorRepository debtors;
    private final ContactUpdateRepository audit;
    private final MisDatosService datos;
    public ContactoPersonalService(DebtorRepository debtors, ContactUpdateRepository audit, MisDatosService datos) {
        this.debtors = debtors; this.audit = audit; this.datos = datos;
    }
    static void exigirDeudor(JwtPrincipal user) {
        if (user == null || !user.isDebtor() || user.rut() == null || user.rut().isBlank())
            throw new ApiException(HttpStatus.FORBIDDEN, "Estos datos son del deudor");
    }
    @Transactional
    public MisDatosResponse telefono(JwtPrincipal user, String phone) {
        exigirDeudor(user);
        String normal = normalizarTelefono(phone);
        Debtor d = debtors.bloquearPorRut(user.rut()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "No hay datos a tu nombre"));
        String actual;
        try { actual = normalizarTelefono(d.getPhone()); }
        catch (ApiException invalid) { actual = d.getPhone(); }
        if (!Objects.equals(actual, normal)) {
            Instant now = Instant.now();
            d.actualizarTelefono(normal, now);
            debtors.save(d);
            audit.save(new ContactUpdate(d.getId(), "PHONE", now));
        }
        return datos.ver(user);
    }
    static String normalizarTelefono(String value) {
        String phone = value == null ? "" : value.strip().replaceAll("[ ()-]", "");
        if (phone.matches("[2-9][0-9]{8}")) phone = "+56" + phone;
        else if (phone.matches("56[2-9][0-9]{8}")) phone = "+" + phone;
        if (!phone.matches("\\+56[2-9][0-9]{8}"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Escribe un telefono chileno de nueve digitos, con +56");
        return phone;
    }
}
