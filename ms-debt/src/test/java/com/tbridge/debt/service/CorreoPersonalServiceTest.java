package com.tbridge.debt.service;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.exception.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CorreoPersonalServiceTest {
    DebtorRepository debtors = mock(DebtorRepository.class);
    EmailChangeRepository changes = mock(EmailChangeRepository.class);
    ContactUpdateRepository audit = mock(ContactUpdateRepository.class);
    ContactNoticeRepository notices = mock(ContactNoticeRepository.class);
    ContactReversionRepository reversions = mock(ContactReversionRepository.class);
    ContactoRateLimit limits = mock(ContactoRateLimit.class);
    AuthClient auth = mock(AuthClient.class);
    MisDatosService datos = mock(MisDatosService.class);
    ContactoHuella hash = new ContactoHuella("test-key-generated-for-471-verification-only");
    CorreoPersonalService service = new CorreoPersonalService(debtors,changes,audit,notices,reversions,limits,hash,auth,datos);
    JwtPrincipal user = new JwtPrincipal("16482337-7",null,"DEBTOR",null,"16482337-7");
    Debtor d; EmailChange pending; String code;
    @BeforeEach void setup() {
        d=new Debtor(); d.setId(1L); d.setRut(user.rut()); d.setEmail("antes@correo.cl");
        when(debtors.bloquearPorRut(user.rut())).thenReturn(Optional.of(d));
        when(changes.saveAndFlush(any())).thenAnswer(i -> { pending=i.getArgument(0); return pending; });
        when(changes.findByIdAndDebtorId(anyString(),eq(1L))).thenAnswer(i -> pending != null && pending.id.equals(i.getArgument(0)) ? Optional.of(pending) : Optional.empty());
        doAnswer(i -> { code=i.getArgument(1); return null; }).when(auth).verificarCorreo(anyString(),anyString());
    }
    void request() { service.solicitar(user," Nuevo@CORREO.cl ","192.0.2.1"); }
    @Test void requestKeepsCurrentEmailAndStoresOnlyHmac() {
        var response=service.solicitar(user,"nuevo@correo.cl","192.0.2.1");
        assertEquals("antes@correo.cl", d.getEmail()); assertNull(d.getEmailUpdatedAt());
        assertEquals(64,pending.codeHash.length()); assertFalse(pending.codeHash.contains(code));
        assertEquals(900, pending.expiresAt.getEpochSecond()-pending.issuedAt.getEpochSecond());
        assertEquals(pending.id,response.solicitudId()); verify(changes).reemplazar(1L); verifyNoInteractions(audit,notices);
    }
    @Test void confirmationUpdatesResetsRejectionsAndQueuesNotice() {
        d.correoNoSalio("rechazo",true,Instant.now()); request();
        service.confirmar(user,pending.id,code);
        assertEquals("nuevo@correo.cl",d.getEmail()); assertNotNull(d.getEmailUpdatedAt());
        assertEquals(0,d.getEmailRejections()); assertNull(d.getEmailFailure()); assertNull(d.getEmailFailedAt());
        assertEquals("CONSUMED",pending.status);
        verify(audit).save(argThat(e -> e.getDebtorId().equals(1L) && "EMAIL".equals(e.getField())));
        verify(notices).save(argThat(n -> n.oldEmail.equals("antes@correo.cl") && n.id.equals(pending.id)));
    }
    @Test void confirmationCreatesSevenDayReversionAndEightCharacterNoticeCode() {
        request();
        Instant antes = Instant.now();
        service.confirmar(user,pending.id,code);

        ArgumentCaptor<ContactReversion> reversa = ArgumentCaptor.forClass(ContactReversion.class);
        ArgumentCaptor<ContactNotice> aviso = ArgumentCaptor.forClass(ContactNotice.class);
        verify(reversions).save(reversa.capture());
        verify(notices).save(aviso.capture());
        ContactReversion guardada = reversa.getValue();
        String codigo = aviso.getValue().revertCode;
        assertEquals(8, codigo.length());
        assertTrue(codigo.chars().allMatch(c -> "23456789ABCDEFGHJKMNPQRSTUVWXYZ".indexOf(c) >= 0));
        assertNotEquals(codigo, guardada.codeHash);
        assertEquals(hash.calcular("EMAIL_REVERT", guardada.id + ":" + guardada.rut + ":" + codigo), guardada.codeHash);
        assertEquals("PENDING", guardada.status);
        assertEquals("antes@correo.cl", guardada.previousEmail);
        assertEquals(guardada.createdAt.plus(Duration.ofDays(7)), guardada.expiresAt);
        assertEquals(codigo, aviso.getValue().revertCode);
    }
    @Test void deshacerRestauraCorreoInvalidaCodigosAuditaYRevocaSesiones() {
        ContactReversion reversa = reversa("2345678A");
        when(reversions.findByRutAndStatus(user.rut(), "PENDING")).thenReturn(java.util.List.of(reversa));

        service.deshacer(user.rut(), "2345678A", "192.0.2.1");

        assertEquals("antes@correo.cl", d.getEmail());
        assertEquals("USED", reversa.status);
        assertNull(reversa.previousEmail);
        assertNotNull(reversa.usedAt);
        verify(reversions).cancelarOtras(1L, reversa.id);
        verify(changes).reemplazar(1L);
        ArgumentCaptor<ContactUpdate> registro = ArgumentCaptor.forClass(ContactUpdate.class);
        verify(audit).save(registro.capture());
        assertEquals("CONTACT_REVERTED", org.springframework.test.util.ReflectionTestUtils.getField(registro.getValue(), "action"));
        verify(auth).revocarSesionesDeDeudor(user.rut());
        verify(debtors).save(d);
    }
    @Test void codigoErradoAumentaIntentosYUsaMensajeGenerico() {
        ContactReversion reversa = reversa("2345678A");
        when(reversions.findByRutAndStatus(user.rut(), "PENDING")).thenReturn(java.util.List.of(reversa));

        ApiException fallo = assertThrows(ApiException.class, () -> service.deshacer(user.rut(), "ZZZZZZZZ", "origen"));

        assertEquals(HttpStatus.BAD_REQUEST, fallo.getStatus());
        assertEquals(CorreoPersonalService.CODIGO_INVALIDO, fallo.getMessage());
        assertEquals(1, reversa.attempts);
        verifyNoInteractions(audit, auth);
    }
    @Test void vencidoYConCincoIntentosNoAceptaReversion() {
        ContactReversion vencida = reversa("2345678A");
        vencida.expiresAt = Instant.now().minusSeconds(1);
        ContactReversion agotada = reversa("2345678B");
        agotada.attempts = 5;
        when(reversions.findByRutAndStatus(user.rut(), "PENDING")).thenReturn(java.util.List.of(vencida, agotada));

        ApiException fallo = assertThrows(ApiException.class, () -> service.deshacer(user.rut(), "2345678A", "origen"));

        assertEquals(CorreoPersonalService.CODIGO_INVALIDO, fallo.getMessage());
        assertEquals(0, vencida.attempts);
        assertEquals(5, agotada.attempts);
        verifyNoInteractions(audit, auth);
    }
    @Test void rutInexistenteUsaElMismoMensajeGenerico() {
        when(debtors.bloquearPorRut("11111111-1")).thenReturn(Optional.empty());
        ApiException fallo = assertThrows(ApiException.class, () -> service.deshacer("11111111-1", "2345678A", "origen"));
        assertEquals(HttpStatus.BAD_REQUEST, fallo.getStatus());
        assertEquals(CorreoPersonalService.CODIGO_INVALIDO, fallo.getMessage());
    }
    @Test void falloDeMsAuthNoImpideDeshacerYSeAplicanAmbosLimites() {
        ContactReversion reversa = reversa("2345678A");
        when(reversions.findByRutAndStatus(user.rut(), "PENDING")).thenReturn(java.util.List.of(reversa));
        doThrow(new RuntimeException("no disponible")).when(auth).revocarSesionesDeDeudor(user.rut());

        assertDoesNotThrow(() -> service.deshacer(user.rut(), "2345678A", "192.0.2.1"));

        verify(limits).tomar(anyString(), eq(10));
        verify(limits).tomar(anyString(), eq(5));
        assertEquals("antes@correo.cl", d.getEmail());
        assertEquals("USED", reversa.status);
    }
    private ContactReversion reversa(String codigo) {
        ContactReversion r = new ContactReversion();
        r.id = "c3a62c70-5c77-4ff7-8986-aadd50f6d8f1";
        r.debtorId = 1L; r.rut = user.rut(); r.previousEmail = "antes@correo.cl";
        r.codeHash = hash.calcular("EMAIL_REVERT", r.id + ":" + r.rut + ":" + codigo);
        r.expiresAt = Instant.now().plus(Duration.ofDays(7)); r.status = "PENDING"; r.createdAt = Instant.now();
        return r;
    }
    @Test void wrongCodesPersistAttemptsAndExhaust() {
        request(); for(int i=0;i<5;i++) assertThrows(ApiException.class,()->service.confirmar(user,pending.id,"AAAAAA"));
        assertEquals(5,pending.attempts);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,assertThrows(ApiException.class,()->service.confirmar(user,pending.id,code)).getStatus());
        assertEquals("antes@correo.cl",d.getEmail()); verifyNoInteractions(audit,notices);
    }
    @Test void expirationRejectsCorrectCode() {
        request(); pending.expiresAt=Instant.now().minusSeconds(1);
        assertEquals(HttpStatus.GONE,assertThrows(ApiException.class,()->service.confirmar(user,pending.id,code)).getStatus());
        assertNull(d.getEmailUpdatedAt());
    }
    @ParameterizedTest @ValueSource(strings={"REPLACED","CONSUMED","FAILED","CANCELLED"})
    void terminalStatesCannotConfirm(String state) {
        request(); pending.status=state;
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->service.confirmar(user,pending.id,code)).getStatus());
        verifyNoInteractions(audit,notices);
    }
    @Test void consumedCannotBeUsedTwice() {
        request(); service.confirmar(user,pending.id,code);
        assertThrows(ApiException.class,()->service.confirmar(user,pending.id,code));
        verify(audit,times(1)).save(any());
    }
    @Test void otherDebtorCannotConfirm() {
        request(); Debtor other=new Debtor();other.setId(2L);other.setRut("18905214-6");
        when(debtors.bloquearPorRut(other.getRut())).thenReturn(Optional.of(other));
        assertThrows(ApiException.class,()->service.confirmar(new JwtPrincipal(other.getRut(),null,"DEBTOR",null,other.getRut()),pending.id,code));
        verifyNoInteractions(audit,notices);
    }
    @Test void deliveryFailureCannotChangeEmail() {
        doThrow(new RuntimeException("unavailable")).when(auth).verificarCorreo(anyString(),anyString());
        assertThrows(ApiException.class,this::request); assertEquals("FAILED",pending.status);
        assertEquals("antes@correo.cl",d.getEmail()); assertThrows(ApiException.class,()->service.confirmar(user,pending.id,"AAAAAA"));
    }
    @ParameterizedTest @ValueSource(strings={"antes@correo.cl","mal","a@b", ""})
    void rejectsInvalidOrCurrent(String email) {
        assertThrows(ApiException.class,()->service.solicitar(user,email,"192.0.2.1")); verifyNoInteractions(auth,changes,audit);
    }
    @Test void cancelInvalidatesChallenge() { request(); service.cancelar(user,pending.id); assertEquals("CANCELLED",pending.status); }
    @Test void purposeAndRequestBindHash() {
        assertNotEquals(hash.calcular("LOGIN","id:code"),hash.calcular("EMAIL_CHANGE_VERIFICATION","id:code"));
        assertNotEquals(hash.calcular("EMAIL_CHANGE_VERIFICATION","one:code"),hash.calcular("EMAIL_CHANGE_VERIFICATION","two:code"));
    }
    @Test void noKeyFailsClosed() {
        assertThrows(ApiException.class,()->new ContactoHuella("").calcular("EMAIL_CHANGE_VERIFICATION","code"));
    }
    @Test void noSessionOrOtherRoleCannotRequestOrConfirm() {
        for (JwtPrincipal role : new JwtPrincipal[]{null,new JwtPrincipal("1",null,"ADMIN",null,user.rut()),new JwtPrincipal("1",null,"CREDITOR",null,user.rut())}) {
            assertThrows(ApiException.class,()->service.solicitar(role,"nuevo@correo.cl","192.0.2.1"));
            assertThrows(ApiException.class,()->service.confirmar(role,"opaque","AAAAAA"));
        }
        verifyNoInteractions(changes,audit,auth);
    }
}
