package com.tbridge.debt.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cifra lo que DataBridge guarda y tiene que poder leer de vuelta: el secreto
 * con que firma los avisos de cada suscripcion. Una huella no sirve para
 * firmar, asi que va cifrado con AES-256-GCM, con una llave que viene de
 * {@code CIFRADO_LLAVE} y nunca toca la base: quien se lleve un respaldo no se
 * lleva los secretos.
 *
 * <p>Un valor cifrado es {@code "enc:v1:" + base64(iv | etiqueta | cifrado)},
 * el mismo formato que usan APOFYX y Patrimonio. Uno sin ese prefijo es de
 * antes de cifrar: se lee tal cual, y {@link CifrarSecretosGuardados} lo cifra
 * al arrancar.
 *
 * <p>Para cambiar {@code CIFRADO_LLAVE} sin perder los secretos, la de antes
 * va en {@code CIFRADO_LLAVE_ANTERIOR}: con ella solo se lee, nunca se cifra, y
 * {@link RecifrarSecretosGuardados} pasa cada secreto a la llave nueva al
 * arrancar. GCM autentica cada valor, asi que probar las dos llaves es seguro:
 * con la equivocada no se obtiene un texto falso, se obtiene un error.
 */
@Component
public class Cifrado {

    public static final String PREFIJO = "enc:v1:";
    private static final int IV = 12;
    private static final int ETIQUETA = 16;

    private final SecretKeySpec llave;
    /** La de antes, solo mientras se cambia CIFRADO_LLAVE. Sin ella, null. */
    private final SecretKeySpec anterior;
    private final SecureRandom azar = new SecureRandom();

    public Cifrado(String llave) {
        this(llave, "");
    }

    @Autowired
    public Cifrado(@Value("${app.cifrado.llave}") String llave,
                   @Value("${app.cifrado.llave-anterior:}") String anterior) {
        this.llave = llaveDe(llave);
        this.anterior = anterior == null || anterior.isBlank() || anterior.equals(llave) ? null : llaveDe(anterior);
    }

    private static SecretKeySpec llaveDe(String texto) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(bytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("La JVM no tiene SHA-256", e);
        }
    }

    public static boolean estaCifrado(String valor) {
        return valor != null && valor.startsWith(PREFIJO);
    }

    /** Si hay una llave anterior: se esta cambiando CIFRADO_LLAVE. */
    public boolean tieneAnterior() {
        return anterior != null;
    }

    /** El valor cifrado. Vacio o ya cifrado, se devuelve igual. */
    public String cifrar(String valor) {
        if (valor == null || valor.isEmpty() || estaCifrado(valor)) return valor;
        try {
            byte[] iv = new byte[IV];
            azar.nextBytes(iv);
            Cipher cifrador = Cipher.getInstance("AES/GCM/NoPadding");
            cifrador.init(Cipher.ENCRYPT_MODE, llave, new GCMParameterSpec(ETIQUETA * 8, iv));
            //  Java deja la etiqueta al final; se guarda delante del cifrado.
            byte[] sellado = cifrador.doFinal(valor.getBytes(StandardCharsets.UTF_8));
            int largo = sellado.length - ETIQUETA;
            byte[] salida = ByteBuffer.allocate(IV + sellado.length)
                    .put(iv)
                    .put(sellado, largo, ETIQUETA)
                    .put(sellado, 0, largo)
                    .array();
            return PREFIJO + Base64.getEncoder().encodeToString(salida);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar", e);
        }
    }

    /**
     * El valor original. Si no estaba cifrado, se devuelve igual. Se prueba con
     * la llave actual y, si no sirve y hay una anterior, con esa.
     */
    public String descifrar(String valor) {
        if (!estaCifrado(valor)) return valor;
        try {
            return abrir(valor, llave);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            if (anterior != null) {
                try {
                    return abrir(valor, anterior);
                } catch (GeneralSecurityException | IllegalArgumentException conLaAnterior) {
                    //  Tampoco con la anterior: el error de abajo lo dice.
                }
            }
            throw new IllegalStateException("No se pudo descifrar: la llave (CIFRADO_LLAVE) no es la misma con que se cifro", e);
        }
    }

    /**
     * Si el valor esta cifrado con la llave anterior, el mismo secreto cifrado
     * con la actual; si ya esta con la actual (o no esta cifrado), null. Si no
     * se abre con ninguna de las dos, falla: ese secreto se perderia.
     */
    public String conLaLlaveActual(String valor) {
        if (!estaCifrado(valor)) return null;
        try {
            abrir(valor, llave);
            return null;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            //  No es de la llave actual: se prueba con la anterior.
        }
        if (anterior == null) {
            throw new IllegalStateException("No se pudo descifrar con CIFRADO_LLAVE, y no hay CIFRADO_LLAVE_ANTERIOR");
        }
        try {
            return cifrar(abrir(valor, anterior));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("No se pudo descifrar ni con CIFRADO_LLAVE ni con CIFRADO_LLAVE_ANTERIOR", e);
        }
    }

    private static String abrir(String valor, SecretKeySpec llave) throws GeneralSecurityException {
        byte[] datos = Base64.getDecoder().decode(valor.substring(PREFIJO.length()));
        byte[] iv = Arrays.copyOfRange(datos, 0, IV);
        byte[] etiqueta = Arrays.copyOfRange(datos, IV, IV + ETIQUETA);
        byte[] cifrado = Arrays.copyOfRange(datos, IV + ETIQUETA, datos.length);
        Cipher descifrador = Cipher.getInstance("AES/GCM/NoPadding");
        descifrador.init(Cipher.DECRYPT_MODE, llave, new GCMParameterSpec(ETIQUETA * 8, iv));
        byte[] sellado = ByteBuffer.allocate(cifrado.length + ETIQUETA).put(cifrado).put(etiqueta).array();
        return new String(descifrador.doFinal(sellado), StandardCharsets.UTF_8);
    }
}
