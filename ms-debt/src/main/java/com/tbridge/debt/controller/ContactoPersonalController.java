package com.tbridge.debt.controller;
import com.tbridge.debt.dto.request.TelefonoRequest;
import com.tbridge.debt.dto.response.MisDatosResponse;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.service.ContactoPersonalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/debts/mis-datos")
@SecurityRequirement(name = com.tbridge.debt.config.OpenApiConfig.JWT)
public class ContactoPersonalController {
    private final ContactoPersonalService contacto;
    public ContactoPersonalController(ContactoPersonalService contacto) { this.contacto = contacto; }
    @PatchMapping("/telefono")
    @Operation(summary = "Cambiar el telefono propio", description = "Solo DEBTOR autenticado. Normaliza moviles y fijos chilenos a E.164; devuelve el numero enmascarado. 400 formato invalido; 401 sin sesion; 403 rol no permitido.")
    public MisDatosResponse telefono(@AuthenticationPrincipal JwtPrincipal user,
                                     @Valid @RequestBody TelefonoRequest body) {
        return contacto.telefono(user, body.telefono());
    }
}
