package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * La ficha de un deudor en el panel (#434): sus datos, sus deudas en todas las
 * empresas y los ultimos correos que se le enviaron. Abrirla queda en la
 * bitacora del panel. Sus pagos los da ms-payments y sus ingresos, ms-auth.
 */
@Schema(description = "La ficha de un deudor para el admin de DataBridge")
public record FichaDeudorResponse(
        @Schema(example = "16482337-7") String rut,
        @Schema(example = "Camila Fuentes Rojas") String nombre,
        @Schema(example = "person") Debtor.Kind tipo,
        @Schema(description = "El correo que registro el acreedor", nullable = true) String correo,
        @Schema(nullable = true) String telefono,
        @Schema(description = "Si quiere el correo que le recuerda una cuota por vencer") boolean recordatorios,
        Correo estadoDelCorreo,
        @Schema(description = "Sus deudas en todas las empresas, la que cambio ultima primero") List<Deuda> deudas,
        @Schema(description = "Los ultimos 50 correos que se le enviaron, el mas reciente primero")
        List<Contacto> correosEnviados
) {

    /** Si su correo llega: lo que guarda ms-debt del ultimo envio que no salio. */
    @Schema(description = "Si el correo del deudor llega")
    public record Correo(
            @Schema(description = "La ultima vez que un correo no salio. Vacio si el ultimo salio", nullable = true)
            Instant ultimoFallo,
            @Schema(description = "Por que no salio, con la direccion tapada", nullable = true) String motivo,
            @Schema(description = "Rechazos seguidos de la direccion", example = "0") int rechazosSeguidos,
            @Schema(description = "Con esos rechazos ya no se le escribe solo, hasta que el acreedor cargue otro correo")
            boolean yaNoSeLeEscribe
    ) {
    }

    /** Una deuda con lo mismo que ve la empresa, mas su campana y quien la cobra. */
    @Schema(description = "Una deuda del deudor")
    public record Deuda(
            @Schema(example = "3") Long id,
            @Schema(example = "CTR-2024-007") String referencia,
            @Schema(example = "Patrimonio Inmuebles") String acreedor,
            @Schema(example = "76418902-7") String acreedorRut,
            @Schema(description = "La empresa que la cobra: la agencia de su campana, o el acreedor si no tiene",
                    example = "APOFYX") String cobra,
            @Schema(nullable = true, example = "Arriendos septiembre") String campana,
            @Schema(example = "Arriendo local comercial") String concepto,
            @Schema(example = "repacted") Debt.Status estado,
            @Schema(example = "UF") Debt.Currency moneda,
            @Schema(example = "115.50") BigDecimal montoOriginal,
            @Schema(description = "La suma de las cuotas pendientes", example = "96.25") BigDecimal saldo,
            @Schema(description = "Lo que se paga hoy para saldarla: el saldo mas la mora", example = "100.45")
            BigDecimal totalHoy,
            int cuotasPagadas,
            int cuotasTotales,
            int cuotasVencidas,
            boolean conConvenio,
            @Schema(description = "La empresa que la cobra esta suspendida") boolean enPausa,
            @Schema(description = "La ultima vez que se le envio el codigo por esta deuda", nullable = true)
            Instant codigoEnviado,
            Instant actualizada
    ) {

        public static Deuda de(Debt deuda, DebtSummaryResponse resumen) {
            String cobra = deuda.getCampaign() != null ? deuda.getCampaign().getAgency().getTradeName()
                    : deuda.getCreditor().getTradeName();
            return new Deuda(deuda.getId(), resumen.externalId(), resumen.acreedor(), resumen.acreedorRut(), cobra,
                    deuda.getCampaign() == null ? null : deuda.getCampaign().getName(), resumen.concepto(),
                    resumen.estado(), resumen.moneda(), resumen.montoOriginal(), resumen.saldo(), resumen.totalHoy(),
                    resumen.cuotasPagadas(), resumen.cuotasTotales(), resumen.cuotasVencidas(), resumen.conConvenio(),
                    resumen.enPausa(), resumen.codigoEnviado(), resumen.actualizada());
        }
    }

    /** Un correo que se le envio, con la direccion tapada como se guardo. */
    @Schema(description = "Un correo enviado al deudor")
    public record Contacto(
            @Schema(example = "campaign") Contact.Kind tipo,
            @Schema(description = "A donde, con la direccion tapada", nullable = true) String destino,
            @Schema(description = "La referencia de la deuda", example = "CTR-2024-007") String referencia,
            @Schema(example = "Patrimonio Inmuebles") String acreedor,
            @Schema(nullable = true) String campana,
            @Schema(description = "El numero del toque de su campana", nullable = true) Short toque,
            Instant cuando
    ) {

        public static Contacto de(Contact contacto) {
            return new Contacto(contacto.getKind(), contacto.getDestination(), contacto.getDebt().getExternalId(),
                    contacto.getDebt().getCreditor().getTradeName(),
                    contacto.getCampaign() == null ? null : contacto.getCampaign().getName(),
                    contacto.getTouchNumber(), contacto.getOccurredAt());
        }
    }
}
