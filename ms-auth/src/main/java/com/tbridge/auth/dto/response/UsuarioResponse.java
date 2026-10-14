package com.tbridge.auth.dto.response;

import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.security.JwtPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Quien tiene la sesion.
 *
 * <p>Un deudor no tiene nombre ni correo en la sesion: entro con su RUT y un
 * codigo, sin cuenta. Esos campos simplemente no vienen.
 */
@Schema(description = "El dueno de la sesion")
public record UsuarioResponse(
        @Schema(description = "En un deudor, su RUT; en el personal, su id", example = "16482337-7")
        String id,

        @Schema(description = "Solo el personal de una empresa", example = "Camila Reyes", nullable = true)
        String nombre,

        @Schema(description = "Solo el personal de una empresa", example = "camila.reyes@apofyx.cl", nullable = true)
        String correo,

        @Schema(description = "Del deudor, o de la empresa del usuario. El admin de DataBridge no tiene",
                example = "16482337-7", nullable = true)
        String rut,

        @Schema(description = "DEBTOR es el deudor; CREDITOR, el usuario de una empresa; ADMIN, el admin de DataBridge",
                example = "DEBTOR", allowableValues = {"DEBTOR", "CREDITOR", "ADMIN"})
        String role
) {

    public static UsuarioResponse deudor(String rut) {
        return new UsuarioResponse(rut, null, null, rut, "DEBTOR");
    }

    public static UsuarioResponse personal(StaffUser staff) {
        return new UsuarioResponse(String.valueOf(staff.getId()), staff.getFullName(), staff.getEmail(),
                staff.getOrgRut(), staff.esAdmin() ? "ADMIN" : "CREDITOR");
    }

    /** Lo que dice el JWT de la peticion en curso. */
    public static UsuarioResponse de(JwtPrincipal principal) {
        return new UsuarioResponse(principal.id(), principal.name(), principal.email(), principal.rut(),
                principal.role());
    }
}
