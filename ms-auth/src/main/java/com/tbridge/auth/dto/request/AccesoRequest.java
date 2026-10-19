package com.tbridge.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;

/**
 * Entrar con codigo y una identidad: RUT del deudor o correo del personal.
 *
 * <p>El formato del RUT y su digito verificador los revisa el servicio: aca
 * solo se exige una identidad y que no sea absurdamente larga.
 */
@Schema(description = "Codigo de acceso y RUT del deudor o correo del personal, no ambos")
public record AccesoRequest(
        @Schema(description = "Con o sin puntos y guion", example = "16.482.337-7")
        @Size(max = 20, message = "El RUT es demasiado largo")
        String rut,

        @Schema(description = "Seis caracteres, sin 0, O, 1, I ni L", example = "K7M2QX")
        @NotBlank(message = "Falta el codigo")
        @Size(max = 16, message = "El codigo es demasiado largo")
        String codigo,

        @Schema(description = "Correo del personal. Se usa en vez del RUT", nullable = true)
        @Email(message = "Correo no valido")
        @Size(max = 254, message = "Correo no valido")
        String correo
) {
    @AssertTrue(message = "Indica tu RUT o tu correo, no ambos")
    public boolean isIdentidadValida() {
        return (rut != null && !rut.isBlank()) != (correo != null && !correo.isBlank());
    }
}
