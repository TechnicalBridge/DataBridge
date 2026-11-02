package com.tbridge.auth.security;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JwtKeyLoaderTest {
    @TempDir Path directory;
    private void pem(String file,String label,byte[] bytes) throws Exception {
        Files.writeString(directory.resolve(file),"-----BEGIN "+label+"-----\n"
                +Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+label+"-----\n");
    }
    private void pair(KeyPair pair) throws Exception {
        pem("private.pem","PRIVATE KEY",pair.getPrivate().getEncoded());
        pem("public.pem","PUBLIC KEY",pair.getPublic().getEncoded());
    }
    @Test void standard_pem_and_additional_public_keys() throws Exception {
        pair(JwtTestTokens.PAIR); Files.createDirectory(directory.resolve("verification"));
        pem("verification/new.pem","PUBLIC KEY",JwtTestTokens.pair("secp256r1").getPublic().getEncoded());
        var ring = new JwtKeyLoader().load(directory);
        assertEquals(2,((List<?>)ring.publicJwks().get("keys")).size());
        assertEquals(JwtTestConfig.ring().activeKid(),ring.activeKid());
    }
    @Test void mismatch() throws Exception {
        pair(JwtTestTokens.PAIR);
        pem("public.pem","PUBLIC KEY",JwtTestTokens.pair("secp256r1").getPublic().getEncoded());
        assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
    }
    @Test void wrong_curve() throws Exception {
        pair(JwtTestTokens.pair("secp384r1"));
        assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
    }
    @Test void rsa_rejected() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair(generator.generateKeyPair());
        assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
    }
    @Test void ed25519_rejected() throws Exception {
        pair(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
    }
    @Test void malformed_or_missing_never_exposes_input() throws Exception {
        assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
        Files.writeString(directory.resolve("private.pem"),"sensitive-input-never-returned");
        var failure=assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
        assertFalse(failure.toString().contains("sensitive-input")); assertNull(failure.getCause());
    }
    @Test void private_key_in_verification_rejected() throws Exception {
        pair(JwtTestTokens.PAIR); Files.createDirectory(directory.resolve("verification"));
        pem("verification/wrong.pem","PRIVATE KEY",JwtTestTokens.PAIR.getPrivate().getEncoded());
        assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
    }
    @Test void additional_wrong_curve_rejected() throws Exception {
        pair(JwtTestTokens.PAIR); Files.createDirectory(directory.resolve("verification"));
        pem("verification/wrong.pem","PUBLIC KEY",JwtTestTokens.pair("secp384r1").getPublic().getEncoded());
        assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
    }
    @Test void encrypted_and_sec1_formats_rejected() throws Exception {
        pair(JwtTestTokens.PAIR);
        pem("private.pem","EC PRIVATE KEY",JwtTestTokens.PAIR.getPrivate().getEncoded());
        assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
        pem("private.pem","ENCRYPTED PRIVATE KEY",JwtTestTokens.PAIR.getPrivate().getEncoded());
        assertThrows(IllegalStateException.class,()->new JwtKeyLoader().load(directory));
    }
}
