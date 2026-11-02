package com.tbridge.auth.security;

import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.regex.Pattern;

/** Carga PKCS#8/SPKI; no genera ni reemplaza material al arrancar. */
public final class JwtKeyLoader {
    public JwtKeyRing load(Path directory) {
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                    pem(directory.resolve("private.pem"), "PRIVATE KEY")));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                    pem(directory.resolve("public.pem"), "PUBLIC KEY")));
            List<PublicKey> additional = new ArrayList<>();
            Path verification = directory.resolve("verification");
            if (Files.exists(verification)) {
                try (var entries = Files.list(verification)) {
                    for (Path path : entries.sorted().toList()) {
                        if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".pem"))
                            throw new IllegalArgumentException();
                        additional.add(factory.generatePublic(new X509EncodedKeySpec(pem(path, "PUBLIC KEY"))));
                    }
                }
            }
            return new JwtKeyRing(privateKey, publicKey, additional);
        } catch (Exception failure) {
            throw new IllegalStateException("No se pudieron cargar las claves JWT: revisa JWT_KEY_DIRECTORY, formato, permisos y pareja P-256");
        }
    }
    private static byte[] pem(Path path, String label) throws Exception {
        String text = Files.readString(path).trim();
        var matcher = Pattern.compile("\\A-----BEGIN " + label + "-----\\s+([A-Za-z0-9+/=\\r\\n]+)\\s+-----END "
                + label + "-----\\z").matcher(text);
        if (!matcher.matches()) throw new IllegalArgumentException();
        return Base64.getDecoder().decode(matcher.group(1).replaceAll("\\s", ""));
    }
}
