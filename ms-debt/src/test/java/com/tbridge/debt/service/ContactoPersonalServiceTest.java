package com.tbridge.debt.service;
import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.exception.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ContactoPersonalServiceTest {
    DebtorRepository debtors = mock(DebtorRepository.class);
    ContactUpdateRepository audit = mock(ContactUpdateRepository.class);
    MisDatosService datos = mock(MisDatosService.class);
    ContactoPersonalService service = new ContactoPersonalService(debtors, audit, datos);
    JwtPrincipal user = new JwtPrincipal("16482337-7", null, "DEBTOR", null, "16482337-7");
    Debtor debtor;
    @BeforeEach void setup() {
        debtor = new Debtor(); debtor.setId(1L); debtor.setRut(user.rut()); debtor.setPhone("+56911111111");
        when(debtors.bloquearPorRut(user.rut())).thenReturn(Optional.of(debtor));
    }
    @ParameterizedTest @ValueSource(strings={"+56 9 8765 4321", "987654321", "56987654321", "+56 (9) 8765-4321"})
    void normalizeMobile(String phone) {
        service.telefono(user, phone);
        assertEquals("+56987654321", debtor.getPhone());
        assertNotNull(debtor.getPhoneUpdatedAt());
        verify(audit).save(argThat(e -> e.getDebtorId().equals(1L) && "DEBTOR".equals(e.getActor()) && "PHONE".equals(e.getField())));
    }
    @Test void acceptsFixedLine() { assertEquals("+56223456789", ContactoPersonalService.normalizarTelefono("(2) 2345 6789")); }
    @ParameterizedTest @ValueSource(strings={"+54987654321", "123", "+56012345678", "+56112345678", "", "no-es-telefono"})
    void rejectsInvalid(String phone) {
        assertThrows(ApiException.class, () -> service.telefono(user, phone));
        assertEquals("+56911111111", debtor.getPhone()); verifyNoInteractions(audit);
    }
    @Test void sameNormalizedValueIsNotAnEdit() {
        service.telefono(user, "+56 9 1111 1111");
        assertNull(debtor.getPhoneUpdatedAt()); verifyNoInteractions(audit); verify(debtors,never()).save(any());
    }
    @ParameterizedTest @ValueSource(strings={"ADMIN", "CREDITOR"})
    void rejectsOtherRoles(String role) {
        assertThrows(ApiException.class, () -> service.telefono(new JwtPrincipal("other", null, role, null, user.rut()), "+56987654321"));
        verifyNoInteractions(debtors, audit);
    }
    @Test void requiresSession() { assertThrows(ApiException.class, () -> service.telefono(null, "+56987654321")); verifyNoInteractions(audit); }
    @Test void cannotChooseOtherDebtor() {
        JwtPrincipal other = new JwtPrincipal("18905214-6", null, "DEBTOR", null, "18905214-6");
        assertThrows(ApiException.class, () -> service.telefono(other, "+56987654321"));
        verify(debtors).bloquearPorRut(other.rut()); verifyNoInteractions(audit);
        assertEquals("+56911111111", debtor.getPhone());
    }
}
