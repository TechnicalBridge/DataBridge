package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.ReglasResponse;
import com.tbridge.auth.dto.response.ReglasResponse.Regla;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** La pantalla Reglas: lo que rige en ms-auth y de que variable sale, sin una clave ni una direccion entera. */
class ReglasServiceTest {

    private static final String CLAVE_DE_BREVO = "xkeysib-clave-de-prueba-que-nunca-se-muestra";
    private static final String CONTRASENA = "abcd efgh ijkl mnop";

    @SuppressWarnings("unchecked")
    private static MailService correo(String host, String clave, String brevo, String soloA, String desviarA,
                                      String buzon) {
        ObjectProvider<JavaMailSender> proveedor = mock(ObjectProvider.class);
        when(proveedor.getIfAvailable()).thenReturn(mock(JavaMailSender.class));
        return new MailService(proveedor, "databridge.equipo@gmail.com", "http://localhost:8080", host, 587, clave,
                "DataBridge", "", soloA, desviarA, buzon, brevo);
    }

    private static Regla regla(ReglasResponse reglas, String que) {
        return reglas.grupos().stream()
                .flatMap(grupo -> grupo.reglas().stream())
                .filter(r -> r.que().startsWith(que))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no esta la regla: " + que));
    }

    @Test
    void cuanto_dura_cada_cosa_y_de_que_variable_sale() {
        ReglasResponse reglas = new ReglasService(24, 15, 48, 168, 15, correo("", "", "", "", "", "")).reglas();

        assertEquals("ms-auth", reglas.servicio());
        assertEquals("24 horas", regla(reglas, "El código de acceso").valor());
        assertEquals("CODE_TTL_HOURS", regla(reglas, "El código de acceso").variable());
        assertEquals("15 minutos", regla(reglas, "El enlace para entrar").valor());
        assertEquals("MAGIC_TTL_MINUTES", regla(reglas, "El enlace para entrar").variable());
        assertEquals("2 días", regla(reglas, "La invitación").valor());
        assertEquals("7 días desde que se entra, se use o no", regla(reglas, "Una sesión dura").valor());
        assertEquals("JWT_TTL_MINUTES", regla(reglas, "El token de la sesión").variable());
    }

    @Test
    void por_brevo_y_en_modo_de_prueba_sin_la_clave_ni_las_direcciones() {
        ReglasResponse reglas = new ReglasService(24, 15, 24, 168, 15, correo("", "", CLAVE_DE_BREVO,
                "deudores.prueba@gmail.com,@empresa-prueba.cl", "databridge.equipo@gmail.com", "")).reglas();

        assertEquals("Por la API de Brevo", regla(reglas, "Por dónde salen").valor());
        assertEquals("BREVO_API_KEY", regla(reglas, "Por dónde salen").variable());
        assertEquals("Encendido: 2 direcciones o dominios reciben directo, y lo demás va al buzón de desvío (hasta 100 por día)",
                regla(reglas, "Modo de prueba").valor());
        assertEquals("DataBridge <da***@gmail.com>", regla(reglas, "Quién los firma").valor());
        String todo = reglas.toString();
        assertFalse(todo.contains(CLAVE_DE_BREVO), "la clave de Brevo");
        assertFalse(todo.contains("deudores.prueba"), "las direcciones del modo de prueba solo se cuentan");
        assertFalse(todo.contains("databridge.equipo@gmail.com"), "ni la cuenta que manda");
    }

    @Test
    void por_smtp_dice_el_servidor_pero_nunca_la_contrasena() {
        ReglasResponse reglas = new ReglasService(24, 15, 24, 168, 15,
                correo("smtp.gmail.com", CONTRASENA, "", "", "", "mailpit:1025")).reglas();

        assertEquals("Por el servidor SMTP smtp.gmail.com:587", regla(reglas, "Por dónde salen").valor());
        assertEquals("Apagado: se le escribe a cualquier dirección", regla(reglas, "Modo de prueba").valor());
        assertFalse(reglas.toString().contains(CONTRASENA));
    }

    @Test
    void el_buzon_de_prueba_y_sin_servidor_tambien_se_dicen_claro() {
        assertEquals("Al buzón de prueba (mailpit:587): no le llegan a nadie", regla(
                new ReglasService(24, 15, 24, 168, 15, correo("mailpit", "", "", "", "", "mailpit:1025")).reglas(),
                "Por dónde salen").valor());
        assertEquals("BREVO_API_KEY · SMTP_HOST", regla(
                new ReglasService(24, 15, 24, 168, 15, correo("", "", "", "", "", "")).reglas(),
                "Por dónde salen").variable());
    }
}
