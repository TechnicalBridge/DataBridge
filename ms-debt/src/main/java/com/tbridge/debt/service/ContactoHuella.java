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
    private final String key;
    public ContactoHuella(@Value("${app.contact-verification-key:}") String key) { this.key = key; }
    public String calcular(String purpose, String value) {
        if (key.length() < 32) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "La verificacion de correo no esta configurada");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo preparar la verificacion");
        }
    }
}
