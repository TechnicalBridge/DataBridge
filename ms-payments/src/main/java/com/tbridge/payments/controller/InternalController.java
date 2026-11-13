package com.tbridge.payments.controller;

import com.tbridge.payments.exception.ApiError;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.config.OpenApiConfig;
import com.tbridge.payments.dto.request.UfManualRequest;
import com.tbridge.payments.dto.response.UfCargaResponse;
import com.tbridge.payments.dto.response.UfResponse;
import com.tbridge.payments.service.UfLoader;
import com.tbridge.payments.service.UfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que operaciones puede hacer con la UF. No es publico: va detras de la
 * clave interna y el gateway no lo expone.
 */
@RestController
@Tag(name = "Interno", description = "La UF, con la clave interna. El gateway no lo expone")
@SecurityRequirement(name = OpenApiConfig.CLAVE_INTERNA)
public class InternalController {

    private final UfService uf;
    private final UfLoader cargador;
    private final String internalKey;

    public InternalController(UfService uf, UfLoader cargador, @Value("${app.internal-key}") String internalKey) {
        this.uf = uf;
        this.cargador = cargador;
        this.internalKey = internalKey;
    }

    @PostMapping("/internal/uf")
    @Operation(summary = "Cargar a mano la UF de un dia",
            description = "Para cuando el Banco Central no esta disponible. Sin la UF del dia, los cobros en UF se detienen.")
    @ApiResponse(responseCode = "200", description = "Valor guardado")
    @ApiResponse(responseCode = "400", description = "Falta el dia o el valor, o no es positivo",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Clave interna inválida",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UfResponse cargarAMano(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @Valid @RequestBody UfManualRequest pedido
    ) {
        exigirClave(clave);
        return uf.cargarAMano(pedido.dia(), pedido.valor());
    }

    @PostMapping("/internal/uf/cargar")
    @Operation(summary = "Cargar ahora la UF desde el Banco Central",
            description = "Sin esperar la carga de las 9:30. Pide de una semana atras a cuarenta dias adelante.")
    @ApiResponse(responseCode = "200", description = "Cuantos dias se cargaron, o por que no se pudo")
    @ApiResponse(responseCode = "401", description = "Clave interna inválida",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UfCargaResponse cargarAhora(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave) {
        exigirClave(clave);
        return cargador.cargar();
    }

    private void exigirClave(String clave) {
        if (clave == null || !clave.equals(internalKey)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Clave interna inválida");
        }
    }
}
