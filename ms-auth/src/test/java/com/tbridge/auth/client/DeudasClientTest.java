package com.tbridge.auth.client;

import com.tbridge.auth.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** El correo registrado sale de ms-debt, con la clave interna; si no responde, no hay correo. */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class DeudasClientTest {

    @Test
    void errores_http_no_registran_datos_y_conservan_resultados(org.springframework.boot.test.system.CapturedOutput salida) {
        servidor.expect(requestTo("http://ms-debt:8083/internal/deudores/16482337-7/correo"))
                .andRespond(withServerError().body("persona@example.org /privado/16482337-7"));
        servidor.expect(requestTo("http://ms-debt:8083/internal/organizaciones/77305118-6"))
                .andRespond(withServerError().body("persona@example.org /privado/77305118-6"));
        assertEquals(Optional.empty(), cliente.correoDelDeudor("16482337-7"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ApiException.class, () -> cliente.empresa("77305118-6")).getStatus());
        servidor.verify();
        org.junit.jupiter.api.Assertions.assertTrue(salida.getAll().contains("category=DEBT_SERVICE_ERROR"));
        org.junit.jupiter.api.Assertions.assertFalse(salida.getAll().contains("persona@example.org"));
        org.junit.jupiter.api.Assertions.assertFalse(salida.getAll().contains("/privado/"));
        org.junit.jupiter.api.Assertions.assertFalse(java.util.regex.Pattern
                .compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matcher(salida.getAll()).find());
    }

    private MockRestServiceServer servidor;
    private DeudasClient cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new DeudasClient(builder, "http://ms-debt:8083/", "clave-interna");
    }

    @Test
    void pregunta_con_la_clave_interna_y_devuelve_el_correo() {
        servidor.expect(requestTo("http://ms-debt:8083/internal/deudores/16482337-7/correo"))
                .andExpect(header("X-Internal-Key", "clave-interna"))
                .andRespond(withSuccess("{\"correo\":\"felipe.rojas@correo.cl\"}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.of("felipe.rojas@correo.cl"), cliente.correoDelDeudor("16482337-7"));
        servidor.verify();
    }

    @Test
    void sin_correo_registrado_no_hay_correo() {
        servidor.expect(requestTo("http://ms-debt:8083/internal/deudores/16482337-7/correo"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertEquals(Optional.empty(), cliente.correoDelDeudor("16482337-7"));
    }

    @Test
    void pregunta_por_una_empresa_con_la_clave_interna() {
        servidor.expect(requestTo("http://ms-debt:8083/internal/organizaciones/77305118-6"))
                .andExpect(header("X-Internal-Key", "clave-interna"))
                .andRespond(withSuccess("{\"rut\":\"77305118-6\",\"nombre\":\"APOFYX\",\"activa\":true}",
                        MediaType.APPLICATION_JSON));

        DeudasClient.Empresa empresa = cliente.empresa("77305118-6").orElseThrow();
        assertEquals("APOFYX", empresa.nombre());
        assertEquals(true, empresa.activa());
        servidor.verify();
    }

    @Test
    void una_empresa_que_no_existe_no_es_un_error() {
        servidor.expect(requestTo("http://ms-debt:8083/internal/organizaciones/76086428-5"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertEquals(Optional.empty(), cliente.empresa("76086428-5"));
    }

    @Test
    void si_ms_debt_falla_no_se_sabe_si_la_empresa_existe_y_se_dice() {
        //  Con el correo de un deudor, callar la falla es lo seguro. Aca no:
        //  darle una persona a una empresa que quizas no existe seria peor.
        servidor.expect(requestTo("http://ms-debt:8083/internal/organizaciones/77305118-6"))
                .andRespond(withServerError());

        ApiException error = assertThrows(ApiException.class, () -> cliente.empresa("77305118-6"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
    }

    @Test
    void si_ms_debt_falla_no_hay_correo_y_no_sale_ningun_enlace() {
        servidor.expect(requestTo("http://ms-debt:8083/internal/deudores/16482337-7/correo"))
                .andRespond(withServerError());

        assertEquals(Optional.empty(), cliente.correoDelDeudor("16482337-7"));
    }
}
