package com.tbridge.debt.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.tbridge.debt.exception.ApiException;
import org.springframework.http.HttpStatus;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
public class ContactoHuella {
    private final String llave;
    public ContactoHuella(@Value("${app.contact-verification-key:}") String llave) { this.llave = llave; }
    public String calcular(String proposito, String valor) {
        if (llave.length() < 32) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "La verificación de correo no está configurada");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(llave.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((proposito + ":" + valor).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo preparar la verificación");
        }
    }
}
