package com.tbridge.auth.controller;
import com.tbridge.auth.service.MailService;
import com.tbridge.auth.service.ContactNoticeService;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.config.OpenApiConfig;
import com.tbridge.auth.util.Correo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/contacto")
@SecurityRequirement(name = OpenApiConfig.CLAVE_INTERNA)
public class ContactoInternoController {
    public record Verificacion(@NotBlank @Size(max=254) String correo,
            @NotBlank @Pattern(regexp="[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}") String codigo) {}
    public record Aviso(@NotBlank @Pattern(regexp="[a-f0-9-]{36}") String eventoId,
                         @NotBlank @Size(max=254) String correo) {}
    private final MailService correo;
    private final ContactNoticeService avisos;
    private final String claveInterna;
    public ContactoInternoController(MailService correo, ContactNoticeService avisos, @Value("${app.internal-key}") String claveInterna) {
        this.correo=correo; this.avisos=avisos; this.claveInterna=claveInterna;
    }
    private void exigir(String claveRecibida) {
        if (claveRecibida == null || !java.security.MessageDigest.isEqual(claveInterna.getBytes(java.nio.charset.StandardCharsets.UTF_8), claveRecibida.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Clave interna inválida");
    }
    private String direccion(String correo) {
        return Correo.normalizar(correo).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Correo inválido"));
    }
    @PostMapping("/verificacion")
    @Operation(summary="Entregar EMAIL_CHANGE_VERIFICATION sin emitir credenciales", description="Solo X-Internal-Key. Exige entrega directa: rechaza Mailpit, dominios reservados, bloqueos y desvíos con 503. No devuelve el código.")
    public void verificar(@RequestHeader(value="X-Internal-Key", required=false) String claveRecibida, @Valid @RequestBody Verificacion solicitud) {
        exigir(claveRecibida); correo.enviarVerificacionDeCorreo(direccion(solicitud.correo()), solicitud.codigo());
    }
    @PostMapping("/aviso")
    @Operation(summary="Aviso privado al correo anterior", description="Solo X-Internal-Key. Deduplica por eventoId persistido. La aceptación ambigua requiere revisión para evitar duplicados.")
    public void avisar(@RequestHeader(value="X-Internal-Key", required=false) String claveRecibida, @Valid @RequestBody Aviso solicitud) {
        exigir(claveRecibida); avisos.enviar(solicitud.eventoId(), direccion(solicitud.correo()));
    }
}
