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
    private final MailService mail;
    private final ContactNoticeService notices;
    private final String key;
    public ContactoInternoController(MailService mail, ContactNoticeService notices, @Value("${app.internal-key}") String key) {
        this.mail=mail; this.notices=notices; this.key=key;
    }
    private void exigir(String supplied) {
        if (supplied == null || !java.security.MessageDigest.isEqual(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Clave interna invalida");
    }
    private String direccion(String email) {
        return Correo.normalizar(email).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Correo invalido"));
    }
    @PostMapping("/verificacion")
    @Operation(summary="Entregar EMAIL_CHANGE_VERIFICATION sin emitir credenciales", description="Solo X-Internal-Key. Exige entrega directa: rechaza Mailpit, dominios reservados, bloqueos y desvios con 503. No devuelve el codigo.")
    public void verificar(@RequestHeader(value="X-Internal-Key", required=false) String supplied, @Valid @RequestBody Verificacion body) {
        exigir(supplied); mail.enviarVerificacionDeCorreo(direccion(body.correo()), body.codigo());
    }
    @PostMapping("/aviso")
    @Operation(summary="Aviso privado al correo anterior", description="Solo X-Internal-Key. Deduplica por eventoId persistido. Aceptacion ambigua requiere revision para evitar duplicados.")
    public void avisar(@RequestHeader(value="X-Internal-Key", required=false) String supplied, @Valid @RequestBody Aviso body) {
        exigir(supplied); notices.enviar(body.eventoId(), direccion(body.correo()));
    }
}
