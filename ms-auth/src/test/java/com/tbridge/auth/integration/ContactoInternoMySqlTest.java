package com.tbridge.auth.integration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIf("com.tbridge.auth.integration.BaseIntegracionMySql#hayBaseDeDatos")
class ContactoInternoMySqlTest extends BaseIntegracionMySql {
    @Test void verificationDoesNotIssueLoginCredentialsOrPublicCode() throws Exception {
        mvc.perform(post("/internal/contacto/verificacion").header("X-Internal-Key",CLAVE_INTERNA)
                .contentType("application/json").content("{\"correo\":\"nuevo@correo.cl\",\"codigo\":\"K7M2QX\"}"))
                .andExpect(status().isOk()).andExpect(content().string(""));
        verify(correo).enviarVerificacionDeCorreo("nuevo@correo.cl","K7M2QX");
        assertThat(jdbc.queryForObject("select count(*) from access_codes",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from magic_links",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sessions",Integer.class)).isZero();
    }
    @Test void internalVerificationRequiresKey() throws Exception {
        mvc.perform(post("/internal/contacto/verificacion").contentType("application/json")
                .content("{\"correo\":\"nuevo@correo.cl\",\"codigo\":\"K7M2QX\"}"))
                .andExpect(status().isUnauthorized());
        verify(correo,never()).enviarVerificacionDeCorreo(anyString(),anyString());
    }
    @Test void previousAddressNoticeDeduplicatesInMysql() throws Exception {
        String id=UUID.randomUUID().toString();
        String body="{\"eventoId\":\""+id+"\",\"correo\":\"anterior@correo.cl\"}";
        for(int i=0;i<2;i++) mvc.perform(post("/internal/contacto/aviso").header("X-Internal-Key",CLAVE_INTERNA)
                .contentType("application/json").content(body)).andExpect(status().isOk());
        verify(correo,times(1)).enviarAvisoDeCambio("anterior@correo.cl");
        assertThat(jdbc.queryForObject("select state from contact_notice_receipts where event_id=?",String.class,id)).isEqualTo("SENT");
    }
    @Test void ambiguousDeliveryDoesNotSendDuplicateOnRetry() throws Exception {
        String id=UUID.randomUUID().toString();
        String body="{\"eventoId\":\""+id+"\",\"correo\":\"anterior@correo.cl\"}";
        doThrow(new com.tbridge.auth.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"No disponible"))
                .when(correo).enviarAvisoDeCambio("anterior@correo.cl");
        for(int i=0;i<2;i++) mvc.perform(post("/internal/contacto/aviso").header("X-Internal-Key",CLAVE_INTERNA)
                .contentType("application/json").content(body)).andExpect(status().isServiceUnavailable());
        verify(correo,times(1)).enviarAvisoDeCambio("anterior@correo.cl");
        assertThat(jdbc.queryForObject("select state from contact_notice_receipts where event_id=?",String.class,id)).isEqualTo("SENDING");
    }
}
