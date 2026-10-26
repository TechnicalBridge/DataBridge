package com.tbridge.auth.controller;
import com.tbridge.auth.security.JwtKeyRing;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class JwksController {
    private final JwtKeyRing keys;
    public JwksController(JwtKeyRing keys) { this.keys = keys; }
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> keys() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(keys.publicJwks());
    }
}
