package com.tbridge.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.payments.client.BancoCentralClient;
import com.tbridge.payments.client.KhipuClient;
import com.tbridge.payments.client.MercadoPagoClient;
import com.tbridge.payments.client.WebpayClient;
import com.tbridge.payments.dto.response.ReglasResponse;
import com.tbridge.payments.dto.response.ReglasResponse.Regla;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** La pantalla Reglas: que pasarelas cobran de verdad, cuanto vale un cobro y de donde sale la UF. Sin credenciales. */
class ReglasServiceTest {

    private static final String LLAVE_KHIPU = "llave-de-khipu-que-nunca-se-muestra";
    private static final String TOKEN_MP = "APP_USR-token-que-nunca-se-muestra";
    private static final String LLAVE_TRANSBANK = "579B532A7440BB0C9079DED94D31EA1615BACEB56610332264630D42D0A36B1C";

    private static Regla regla(ReglasResponse reglas, String que) {
        return reglas.grupos().stream()
                .flatMap(grupo -> grupo.reglas().stream())
                .filter(r -> r.que().equals(que))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no esta la regla: " + que));
    }

    private static KhipuClient khipu(boolean real) {
        KhipuClient khipu = mock(KhipuClient.class);
        when(khipu.real()).thenReturn(real);
        return khipu;
    }

    private static MercadoPagoClient mercadoPago(boolean real) {
        MercadoPagoClient mp = mock(MercadoPagoClient.class);
        when(mp.real()).thenReturn(real);
        when(mp.testMode()).thenReturn(true);
        return mp;
    }

    private static BancoCentralClient bancoCentral(String usuario, String clave) {
        return new BancoCentralClient(new ObjectMapper(), "https://si3.bcentral.cl/SieteRestWS/SieteRestWS.ashx",
                usuario, clave);
    }

    @Test
    void conectadas_dicen_en_cuanto_vence_un_cobro_y_de_que_variable_sale() {
        WebpayClient webpay = new WebpayClient("https://webpay3gint.transbank.cl", "597055555532", LLAVE_TRANSBANK, "TEST");
        ReglasResponse reglas = new ReglasService(webpay, khipu(true), mercadoPago(true), bancoCentral("u", "c"),
                Duration.ofMinutes(30), Duration.ofHours(1)).reglas();

        assertEquals("ms-payments", reglas.servicio());
        assertEquals("Conectada a Transbank, en integración: se paga con tarjetas de prueba. Un cobro vence en 15 minutos",
                regla(reglas, "Webpay").valor());
        assertEquals("Conectada a Khipu. Un cobro vence en 30 minutos", regla(reglas, "Khipu").valor());
        assertEquals("KHIPU_LLAVE · KHIPU_VENCE_EN", regla(reglas, "Khipu").variable());
        assertEquals("Conectada a Mercado Pago, en su ambiente de prueba. Un cobro vence en 1 hora",
                regla(reglas, "Mercado Pago").valor());
        assertEquals("Del Banco Central, todos los días", regla(reglas, "De dónde sale el valor del día").valor());
    }

    @Test
    void sin_credenciales_quedan_simuladas_y_la_uf_se_carga_a_mano() {
        WebpayClient webpay = new WebpayClient("https://webpay3gint.transbank.cl", "597055555532", LLAVE_TRANSBANK,
                "SIMULADA");
        ReglasResponse reglas = new ReglasService(webpay, khipu(false), mercadoPago(false), bancoCentral("", ""),
                Duration.ofMinutes(30), Duration.ofMinutes(30)).reglas();

        assertTrue(regla(reglas, "Webpay").valor().startsWith("Simulada"));
        assertTrue(regla(reglas, "Khipu").valor().endsWith("Falta la llave de una cuenta de cobro"));
        assertTrue(regla(reglas, "Mercado Pago").valor().endsWith("Falta el access token"));
        assertTrue(regla(reglas, "De dónde sale el valor del día").valor().startsWith("A mano"));
    }

    @Test
    void en_produccion_lo_dice_y_ninguna_credencial_aparece() {
        WebpayClient webpay = new WebpayClient("https://webpay3g.transbank.cl", "597055555532", LLAVE_TRANSBANK, "LIVE");
        ReglasResponse reglas = new ReglasService(webpay, khipu(true), mercadoPago(true), bancoCentral("u", "c"),
                Duration.ofMinutes(30), Duration.ofMinutes(30)).reglas();

        assertEquals("Conectada a Transbank, en producción. Un cobro vence en 9 minutos", regla(reglas, "Webpay").valor());
        String todo = reglas.toString();
        for (String secreto : new String[]{LLAVE_KHIPU, TOKEN_MP, LLAVE_TRANSBANK, "597055555532"}) {
            assertFalse(todo.contains(secreto), secreto);
        }
    }

    @Test
    void la_duracion_se_dice_como_la_diria_una_persona() {
        assertEquals("1 minuto", ReglasService.duracion(Duration.ofMinutes(1)));
        assertEquals("15 minutos", ReglasService.duracion(Duration.ofMinutes(15)));
        assertEquals("2 horas", ReglasService.duracion(Duration.ofHours(2)));
        assertEquals("90 minutos", ReglasService.duracion(Duration.ofMinutes(90)));
    }
}
