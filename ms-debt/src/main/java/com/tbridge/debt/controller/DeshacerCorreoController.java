package com.tbridge.debt.controller;

import com.tbridge.debt.service.ContactoOrigen;
import com.tbridge.debt.service.CorreoPersonalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Deshacer un cambio de correo que el deudor no pidio (#471). Va sin sesion, porque quien la perdio no puede
 * entrar: lo que prueba que es el dueno es el codigo que le llego al correo anterior.
 */
@RestController
@RequestMapping("/api/debts/publico/correo")
public class DeshacerCorreoController {
    public record Deshacer(@NotBlank @Size(max = 14) String rut, @NotBlank @Size(max = 12) String codigo) {}
    private final CorreoPersonalService servicio;
    private final ContactoOrigen origen;
    public DeshacerCorreoController(CorreoPersonalService servicio, ContactoOrigen origen) {
        this.servicio = servicio; this.origen = origen;
    }

    @PostMapping("/deshacer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deshacer un cambio de correo que no pediste",
            description = "Sin sesion. Con el RUT y el codigo de 8 caracteres que llego al correo anterior, valido por "
                    + "7 dias: vuelve el correo anterior y se cierran todas las sesiones del deudor. 400 si el codigo no "
                    + "sirve, con el mismo mensaje exista o no el RUT; cinco fallos lo dejan sin efecto. 429 si se pasa "
                    + "el limite por origen (10) o por RUT (5) cada 15 minutos.")
    @ApiResponse(responseCode = "204", description = "El correo anterior volvio y se cerraron las sesiones")
    public void deshacer(@Valid @RequestBody Deshacer pedido, HttpServletRequest peticion) {
        servicio.deshacer(pedido.rut(), pedido.codigo(), origen.de(peticion));
    }
}
