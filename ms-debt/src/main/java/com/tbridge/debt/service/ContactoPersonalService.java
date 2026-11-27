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
    private final DebtorRepository deudores;
    private final ContactUpdateRepository bitacora;
    private final MisDatosService datos;
    public ContactoPersonalService(DebtorRepository deudores, ContactUpdateRepository bitacora, MisDatosService datos) {
        this.deudores = deudores; this.bitacora = bitacora; this.datos = datos;
    }
    static void exigirDeudor(JwtPrincipal deudor) {
        if (deudor == null || !deudor.isDebtor() || deudor.rut() == null || deudor.rut().isBlank())
            throw new ApiException(HttpStatus.FORBIDDEN, "Estos datos son del deudor");
    }
    @Transactional
    public MisDatosResponse telefono(JwtPrincipal deudor, String telefono) {
        exigirDeudor(deudor);
        String telefonoNormalizado = normalizarTelefono(telefono);
        Debtor d = deudores.bloquearPorRut(deudor.rut()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "No hay datos a tu nombre"));
        String telefonoActual;
        try { telefonoActual = normalizarTelefono(d.getPhone()); }
        catch (ApiException invalido) { telefonoActual = d.getPhone(); }
        if (!Objects.equals(telefonoActual, telefonoNormalizado)) {
            Instant ahora = Instant.now();
            d.actualizarTelefono(telefonoNormalizado, ahora);
            deudores.save(d);
            bitacora.save(new ContactUpdate(d.getId(), "PHONE", ahora));
        }
        return datos.ver(deudor);
    }
    static String normalizarTelefono(String telefono) {
        String telefonoLimpio = telefono == null ? "" : telefono.strip().replaceAll("[ ()-]", "");
        if (telefonoLimpio.matches("[2-9][0-9]{8}")) telefonoLimpio = "+56" + telefonoLimpio;
        else if (telefonoLimpio.matches("56[2-9][0-9]{8}")) telefonoLimpio = "+" + telefonoLimpio;
        if (!telefonoLimpio.matches("\\+56[2-9][0-9]{8}"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Escribe un teléfono chileno de nueve dígitos, con +56");
        return telefonoLimpio;
    }
}
