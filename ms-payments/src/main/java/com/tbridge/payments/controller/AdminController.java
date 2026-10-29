package com.tbridge.payments.controller;

import com.tbridge.payments.config.OpenApiConfig;
import com.tbridge.payments.dto.request.UfManualRequest;
import com.tbridge.payments.dto.response.EstadoResponse;
import com.tbridge.payments.dto.response.OperacionResponse;
import com.tbridge.payments.dto.response.ReglasResponse;
import com.tbridge.payments.dto.response.UfResponse;
import com.tbridge.payments.exception.ApiError;
import com.tbridge.payments.security.JwtPrincipal;
import com.tbridge.payments.service.OperacionService;
import com.tbridge.payments.service.ReglasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que el panel de administracion de DataBridge le pide a ms-payments.
 *
 * <p>Todo lo que cuelga de {@code /api/admin} es solo para el admin de
 * DataBridge: lo exige {@code SecurityConfig}, no cada metodo, para que un
 * endpoint nuevo no pueda quedar abierto por olvido. El panel muestra totales
 * por empresa, nunca los datos de un deudor.
 */
@RestController
@RequestMapping("/api/admin/payments")
@Tag(name = "Panel de administracion", description = "Solo para el admin de DataBridge")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class AdminController {

    private final ReglasService reglas;
    private final OperacionService operacion;

    public AdminController(ReglasService reglas, OperacionService operacion) {
        this.reglas = reglas;
        this.operacion = operacion;
    }

    // ------------------------------------------------------------------
    //  La operacion de los pagos (#139)
    // ------------------------------------------------------------------

    @GetMapping("/operacion")
    @Operation(summary = "Como esta la operacion de los pagos",
            description = "Cuantos pagos estan abiertos (y cuantos llevan mas de una hora), cuantos vencieron en las "
                    + "ultimas 24 horas y cuantos duplicados falta devolver; la UF de hoy en Chile, si ya esta; y "
                    + "los avisos de pago a ms-debt que no se han entregado. Solo totales: ningun dato de un deudor.")
    @ApiResponse(responseCode = "200", description = "La operacion de los pagos")
    public OperacionResponse operacion() {
        return operacion.operacion();
    }

    @PostMapping("/avisos/{id}/reintentar")
    @Operation(summary = "Volver a poner en cola un aviso de pago a ms-debt",
            description = "Queda pendiente para ahora y el despachador lo entrega en su pasada siguiente (cada 15 "
                    + "segundos). ms-debt abona una sola vez aunque el aviso le llegue dos. Queda anotado quien lo hizo.")
    @ApiResponse(responseCode = "200", description = "El aviso, ya en cola")
    @ApiResponse(responseCode = "404", description = "No hay ningun aviso con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Ese aviso ya se entrego",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public OperacionResponse.Aviso reintentarAviso(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin, @PathVariable Long id) {
        return operacion.reintentarAviso(id, admin.email());
    }

    @PostMapping("/uf")
    @Operation(summary = "Cargar a mano la UF de un dia",
            description = "Para cuando el Banco Central no la entrego. Queda como la UF de ese dia y es la que usan "
                    + "los cobros en UF. Si ese dia ya tenia valor, lo reemplaza. Queda anotado quien la cargo.")
    @ApiResponse(responseCode = "200", description = "La UF guardada")
    @ApiResponse(responseCode = "400", description = "Falta el dia o el valor, o no es positivo",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UfResponse cargarUf(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
                               @Valid @RequestBody UfManualRequest pedido) {
        return operacion.cargarUf(pedido.dia(), pedido.valor(), admin.email());
    }

    @GetMapping("/reglas")
    @Operation(summary = "Las reglas de pago que rigen",
            description = "Que pasarelas cobran de verdad y cuales quedan simuladas, en cuanto vence un cobro en "
                    + "cada una y de donde sale la UF. De las credenciales dice solo si estan. Cada regla dice de "
                    + "que variable sale. Se leen al arrancar: cambiar una pide reiniciar el servicio.")
    @ApiResponse(responseCode = "200", description = "Las reglas, por grupo")
    public ReglasResponse reglas() {
        return reglas.reglas();
    }

    @GetMapping("/estado")
    @Operation(summary = "Si ms-payments responde",
            description = "La portada del panel lo llama para mostrar que servicios estan arriba.")
    @ApiResponse(responseCode = "200", description = "El servicio responde")
    @ApiResponse(responseCode = "401", description = "Sin sesion",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "La sesion no es de un admin de DataBridge",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EstadoResponse estado() {
        return new EstadoResponse("ms-payments", true);
    }
}
