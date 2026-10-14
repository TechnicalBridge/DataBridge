package com.tbridge.auth.controller;

import com.tbridge.auth.config.OpenApiConfig;
import com.tbridge.auth.dto.request.EmitirCodigoRequest;
import com.tbridge.auth.dto.response.CodigoEmitidoResponse;
import com.tbridge.auth.dto.response.PruebaDeCorreoResponse;
import com.tbridge.auth.service.AuthService;
import com.tbridge.auth.service.MailService;
import com.tbridge.auth.exception.ApiError;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.util.Correo;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lo que el motor de campana le pide a ms-auth.
 *
 * <p>Emitir un codigo no lo hace el deudor: lo hace la campana, y despues lo
 * manda por WhatsApp y por correo. Por eso va detras de la clave interna y el
 * gateway no lo expone.
 */
@RestController
@Tag(name = "Interno", description = "Para los otros servicios, con la clave interna. El gateway no lo expone")
@SecurityRequirement(name = OpenApiConfig.CLAVE_INTERNA)
public class InternalController {

    private final AuthService auth;
    private final MailService correo;
    private final String internalKey;

    public InternalController(AuthService auth, MailService correo,
                              @Value("${app.internal-key}") String internalKey) {
        this.auth = auth;
        this.correo = correo;
        this.internalKey = internalKey;
    }

    @PostMapping("/internal/codigos")
    @Operation(summary = "Emitir un codigo de acceso",
            description = """
                    Genera el codigo para un RUT y lo devuelve una sola vez: en la base queda solo su huella. \
                    Si entre los canales esta el correo y viene la direccion, el correo sale desde aqui.""")
    @ApiResponse(responseCode = "200", description = "Codigo emitido")
    @ApiResponse(responseCode = "400", description = "RUT no valido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Clave interna invalida",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CodigoEmitidoResponse emitir(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @Valid @RequestBody EmitirCodigoRequest pedido
    ) {
        exigirClave(clave);
        List<String> canales = pedido.canalesOPorOmision();
        CodigoEmitidoResponse emitido = auth.emitirCodigo(pedido.rut(), canales, pedido.paraQue());

        //  El envio por correo sale de aqui; el de WhatsApp lo hara el motor
        //  de campana cuando exista, con el mismo codigo.
        if (canales.contains("correo") && pedido.correo() != null) {
            if (pedido.vence() != null) {
                correo.enviarRecordatorio(pedido.correo(), emitido.codigo(), pedido.acreedor(), pedido.vence());
            } else {
                correo.enviarCodigo(pedido.correo(), emitido.codigo(), pedido.acreedor());
            }
        }
        return emitido;
    }

    @PostMapping("/internal/correo/prueba")
    @Operation(summary = "Mandar un correo de prueba",
            description = """
                    Manda un correo ahora, con el servidor que tiene configurado el servicio, y dice como le fue:                     si salio, a donde llego de verdad (en modo de prueba puede ser el buzon de desvio) y con que                     remitente; y si no salio, lo que respondio el servidor. Sirve para saber si las credenciales                     del `.env` funcionan sin esperar a que un deudor no reciba su codigo. Nunca devuelve la clave.""")
    @ApiResponse(responseCode = "200", description = "Como le fue: `enviado` dice si salio")
    @ApiResponse(responseCode = "400", description = "La direccion no es un correo valido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Clave interna invalida",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PruebaDeCorreoResponse probarCorreo(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @Parameter(description = "A quien mandarle la prueba", example = "equipo@gmail.com")
            @RequestParam String para
    ) {
        exigirClave(clave);
        //  En una URL, "+" llega como un espacio: cuenta+alias@gmail.com se leeria
        //  "cuenta alias@gmail.com". Un correo no lleva espacios, asi que se devuelven.
        String escrita = para == null ? "" : para.strip().replace(' ', '+');
        String destino = Correo.normalizar(escrita).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "Esa direccion no es un correo valido"));
        return correo.probar(destino);
    }

    private void exigirClave(String clave) {
        if (clave == null || !clave.equals(internalKey)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Clave interna invalida");
        }
    }
}
