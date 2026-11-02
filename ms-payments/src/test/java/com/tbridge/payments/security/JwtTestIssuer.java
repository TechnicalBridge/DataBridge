package com.tbridge.payments.security;

import java.util.Map;

/** Emisor solo de pruebas. Ninguna privada se carga en el servicio real. */
public final class JwtTestIssuer {
    public String issue(String id,String email,String role,String name,String rut) {
        Map<String,Object> claims=JwtTestTokens.claims();
        claims.put("sub",id); claims.put("id",id); claims.put("role",role);
        claims.remove("rut");
        if(email!=null) claims.put("email",email);
        if(name!=null) claims.put("name",name);
        if(rut!=null) claims.put("rut",rut);
        return JwtTestTokens.token(claims,JwtTestTokens.PAIR,JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(),"ES256");
    }
}
