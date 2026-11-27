package com.tbridge.debt.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ContactoOrigenTest {
    @Test void untrustedHeadersCannotCreateBuckets() {
        assertEquals("203.0.113.1",new ContactoOrigen("").resolver("203.0.113.1","192.0.2.1"));
    }
    @Test void trustedGatewayUsesLastUntrustedHop() {
        assertEquals("192.0.2.1",new ContactoOrigen("127[.]0[.]0[.]1|10[.].*")
                .resolver("10.0.0.2","203.0.113.100,192.0.2.1,127.0.0.1"));
    }
}
