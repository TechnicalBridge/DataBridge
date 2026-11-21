package com.tbridge.debt.controller;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.service.CorreoPersonalService;
import com.tbridge.debt.dto.response.MisDatosResponse;
import com.tbridge.debt.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/debts/mis-datos/correo")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class CorreoPersonalController {
    public record Solicitud(@NotBlank @Size(max=254) String correo) {}
    public record Confirmacion(@NotBlank @Size(max=36) String solicitudId, @NotBlank @Size(max=12) String codigo) {}
    public record Cancelacion(@NotBlank @Size(max=36) String solicitudId) {}
    private final CorreoPersonalService service;
    private final com.tbridge.debt.service.ContactoOrigen origen;
    public CorreoPersonalController(CorreoPersonalService service, com.tbridge.debt.service.ContactoOrigen origen) {
        this.service=service; this.origen=origen;
    }
    @PostMapping("/verificacion")
    @Operation(summary="Verificar un correo nuevo", description="Solo DEBTOR autenticado, identidad del JWT. No cambia el correo vigente. Codigo separado de acceso: 15 minutos, 5 intentos, un solo uso. Sustituye solicitudes anteriores. Limites compartidos por 15 minutos: 3/deudor y 10/origen. 400 direccion invalida o actual; 429 limite; 503 envio bloqueado, redirigido o sin configurar.")
    public CorreoPersonalService.Pendiente solicitar(@AuthenticationPrincipal JwtPrincipal user,
            @Valid @RequestBody Solicitud body, HttpServletRequest request) {
        return service.solicitar(user, body.correo(), origen.de(request));
    }
    @PostMapping("/confirmacion")
    @Operation(summary="Confirmar el cambio de correo", description="Solo el propietario autenticado. 400 codigo incorrecto; 409 solicitud sustituida/consumida; 410 vencido; 429 intentos agotados. Respuesta con contactos enmascarados.")
    public MisDatosResponse confirmar(@AuthenticationPrincipal JwtPrincipal user, @Valid @RequestBody Confirmacion body) {
        return service.confirmar(user, body.solicitudId(), body.codigo());
    }
    @PostMapping("/cancelacion")
    @Operation(summary="Cancelar la verificacion propia")
    public void cancelar(@AuthenticationPrincipal JwtPrincipal user, @Valid @RequestBody Cancelacion body) {
        service.cancelar(user, body.solicitudId());
    }
}
