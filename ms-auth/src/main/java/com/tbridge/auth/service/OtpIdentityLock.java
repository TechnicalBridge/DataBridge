package com.tbridge.auth.service;

import com.tbridge.auth.util.Hash;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Franjas preexistentes: serializa incluso la primera solicitud entre instancias, sin guardar identidades. */
@Component
public class OtpIdentityLock {
    private final JdbcTemplate db;
    public OtpIdentityLock(JdbcTemplate db) { this.db = db; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String identity) {
        int stripe = Integer.parseInt(Hash.sha256(identity).substring(0, 2), 16);
        db.queryForObject("select id from otp_identity_locks where id = ? for update", Integer.class, stripe);
    }
}
