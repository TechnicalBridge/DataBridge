package com.tbridge.payments.security;

/**
 * Quien viene en el token.
 *
 * <p>El RUT es lo que acota lo que cada quien ve: al deudor, sus deudas; a una
 * empresa, su cartera. En la sesion de un deudor es su propio RUT; en la del
 * personal de una empresa, el RUT de la empresa.
 */
public record JwtPrincipal(String id, String email, String role, String name, String rut) {

    public boolean isCreditor() {
        return "CREDITOR".equalsIgnoreCase(role);
    }

    public boolean isDebtor() {
        return "DEBTOR".equalsIgnoreCase(role);
    }

    /** El admin de DataBridge: entra solo al panel de administracion. */
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
