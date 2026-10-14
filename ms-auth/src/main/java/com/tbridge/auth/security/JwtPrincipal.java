package com.tbridge.auth.security;

/**
 * Quien viene en el token.
 *
 * <p>El RUT es lo que acota lo que cada quien ve: al deudor, sus deudas; a una
 * empresa, su cartera. En la sesion de un deudor es su propio RUT; en la del
 * personal de una empresa, el RUT de la empresa. El admin de DataBridge no
 * tiene: no es de ninguna empresa.
 */
public record JwtPrincipal(String id, String email, String role, String name, String rut) {

    public boolean isCreditor() {
        return "CREDITOR".equalsIgnoreCase(role);
    }

    public boolean isDebtor() {
        return "DEBTOR".equalsIgnoreCase(role);
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
