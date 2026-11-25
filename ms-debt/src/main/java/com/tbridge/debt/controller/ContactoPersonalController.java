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
    @Operation(summary = "Cambiar el teléfono propio", description = "Solo DEBTOR autenticado. Normaliza móviles y fijos chilenos a E.164; devuelve el número enmascarado. 400 formato inválido; 401 sin sesión; 403 rol no permitido.")
    public MisDatosResponse telefono(@AuthenticationPrincipal JwtPrincipal deudor,
                                     @Valid @RequestBody TelefonoRequest solicitud) {
        return contacto.telefono(deudor, solicitud.telefono());
    }
}
