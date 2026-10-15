package com.tbridge.payments.service;

import com.tbridge.payments.client.BancoCentralClient;
import com.tbridge.payments.client.KhipuClient;
import com.tbridge.payments.client.MercadoPagoClient;
import com.tbridge.payments.client.WebpayClient;
import com.tbridge.payments.dto.response.ReglasResponse;
import com.tbridge.payments.dto.response.ReglasResponse.Grupo;
import com.tbridge.payments.dto.response.ReglasResponse.Regla;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Las reglas de pago que rigen en ms-payments, para la pantalla Reglas del
 * panel: que pasarelas cobran de verdad y cuales quedan simuladas, en cuanto
 * vence un cobro en cada una, y de donde sale la UF. De las credenciales dice
 * solo si estan: nunca una llave, un token ni una clave.
 */
@Service
public class ReglasService {

    private final WebpayClient webpay;
    private final KhipuClient khipu;
    private final MercadoPagoClient mercadoPago;
    private final BancoCentralClient bancoCentral;
    private final Duration khipuVence;
    private final Duration mercadoPagoVence;

    public ReglasService(WebpayClient webpay, KhipuClient khipu, MercadoPagoClient mercadoPago,
                         BancoCentralClient bancoCentral,
                         @Value("${app.khipu.vence-en:30m}") Duration khipuVence,
                         @Value("${app.mercadopago.vence-en:30m}") Duration mercadoPagoVence) {
        this.webpay = webpay;
        this.khipu = khipu;
        this.mercadoPago = mercadoPago;
        this.bancoCentral = bancoCentral;
        this.khipuVence = khipuVence;
        this.mercadoPagoVence = mercadoPagoVence;
    }

    public ReglasResponse reglas() {
        String simulada = "Simulada: el cobro se confirma en una página de DataBridge y no vence";
        Grupo pasarelas = new Grupo("Pasarelas", List.of(
                Regla.de("Webpay", !webpay.real() ? simulada
                                : "Conectada a Transbank, " + (webpay.produccion() ? "en producción"
                                : "en integración: se paga con tarjetas de prueba")
                                + ". Un cobro vence en " + duracion(webpay.plazoDePago()),
                        "TRANSBANK_ENVIRONMENT · TRANSBANK_API_URL · TRANSBANK_VENCE_EN"),
                Regla.de("Khipu", !khipu.real() ? simulada + ". Falta la llave de una cuenta de cobro"
                                : "Conectada a Khipu. Un cobro vence en " + duracion(khipuVence),
                        "KHIPU_LLAVE · KHIPU_VENCE_EN"),
                Regla.de("Mercado Pago", !mercadoPago.real() ? simulada + ". Falta el access token"
                                : "Conectada a Mercado Pago"
                                        + (mercadoPago.testMode() ? ", en su ambiente de prueba" : "")
                                + ". Un cobro vence en " + duracion(mercadoPagoVence),
                        "MERCADOPAGO_ACCESS_TOKEN · MERCADOPAGO_ENVIRONMENT · MERCADOPAGO_VENCE_EN")));
        Grupo uf = new Grupo("UF", List.of(
                Regla.de("De dónde sale el valor del día", bancoCentral.configurado()
                                ? "Del Banco Central, todos los días"
                                : "A mano: faltan las credenciales del Banco Central. Sin la UF del día, "
                                        + "una deuda en UF no se puede pagar",
                        "BCENTRAL_USER · BCENTRAL_PASS · UF_CRON")));
        return new ReglasResponse("ms-payments", List.of(pasarelas, uf));
    }

    /** 30 minutos; 1 hora; 90 minutos. */
    static String duracion(Duration plazo) {
        long minutos = plazo.toMinutes();
        if (minutos > 0 && minutos % 60 == 0) {
            long horas = minutos / 60;
            return horas + (horas == 1 ? " hora" : " horas");
        }
        return minutos + (minutos == 1 ? " minuto" : " minutos");
    }
}
