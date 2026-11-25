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
    @Operation(summary="Verificar un correo nuevo", description="Solo DEBTOR autenticado, identidad del JWT. No cambia el correo vigente. Código separado de acceso: 15 minutos, 5 intentos, un solo uso. Sustituye solicitudes anteriores. Límites compartidos por 15 minutos: 3/deudor y 10/origen. 400 dirección inválida o actual; 429 límite; 503 envío bloqueado, redirigido o sin configurar.")
    public CorreoPersonalService.Pendiente solicitar(@AuthenticationPrincipal JwtPrincipal deudor,
            @Valid @RequestBody Solicitud solicitud, HttpServletRequest peticion) {
        return service.solicitar(deudor, solicitud.correo(), origen.de(peticion));
    }
    @PostMapping("/confirmacion")
    @Operation(summary="Confirmar el cambio de correo", description="Solo el propietario autenticado. 400 código incorrecto; 409 solicitud sustituida/consumida; 410 vencido; 429 intentos agotados. Respuesta con contactos enmascarados.")
    public MisDatosResponse confirmar(@AuthenticationPrincipal JwtPrincipal deudor, @Valid @RequestBody Confirmacion solicitud) {
        return service.confirmar(deudor, solicitud.solicitudId(), solicitud.codigo());
    }
    @PostMapping("/cancelacion")
    @Operation(summary="Cancelar la verificación propia")
    public void cancelar(@AuthenticationPrincipal JwtPrincipal deudor, @Valid @RequestBody Cancelacion solicitud) {
        service.cancelar(deudor, solicitud.solicitudId());
    }
}
