package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Una deuda en la lista del portal.
 *
 * <p>El saldo se calcula (las cuotas pendientes), nunca se guarda: un total
 * almacenado al lado de sus partes termina discrepando de ellas.
 */
@Relation(collectionRelation = "debts", itemRelation = "debt")
@Schema(description = "Una deuda, con su saldo al dia")
public record DebtSummaryResponse(
        @Schema(example = "3") Long id,
        @Schema(description = "El id que le puso el acreedor. Viaja intacto por toda la cadena", example = "CTR-2024-007")
        String externalId,
        @Schema(example = "Patrimonio Inmuebles") String acreedor,
        @Schema(example = "76418902-7") String acreedorRut,
        @Schema(example = "Comercial Nandu SpA") String deudor,
        @Schema(example = "76991245-2") String deudorRut,
        @Schema(example = "Arriendo local comercial") String concepto,
        @Schema(example = "UF") Debt.Currency moneda,
        @Schema(example = "115.50") BigDecimal montoOriginal,
        @Schema(description = "La suma de las cuotas pendientes", example = "96.25") BigDecimal saldo,
        @Schema(example = "19.25") BigDecimal pagado,
        @Schema(example = "repacted") Debt.Status estado,
        Instant actualizada,
        @Schema(description = "Cuotas ya pagadas. Con cuotasTotales, el avance del convenio", example = "1")
        int cuotasPagadas,
        @Schema(description = "Cuotas vigentes: las pagadas mas las pendientes. Las anuladas no cuentan, y en un "
                + "convenio tampoco una cuota aparte", example = "6")
        int cuotasTotales,
        @Schema(description = "Si se paga (o se pago) con un convenio de cuotas", example = "true")
        boolean conConvenio,
        @Schema(description = "Cuotas pendientes que ya vencieron. En un convenio, las que hay que ponerse al dia",
                example = "1")
        int cuotasVencidas,
        @Schema(description = "La ultima vez que se le envio el codigo de acceso, sola (al entrar la deuda) o "
                + "desde el portal. Solo para la empresa; vacio si nunca se le envio", nullable = true)
        Instant codigoEnviado,
        @Schema(description = "Solo si la deuda esta en disputa: el motivo, lo que explico el deudor y desde cuando",
                nullable = true)
        Disputa disputa,
        @Schema(description = "El interes mensual que pacto el acreedor, en porcentaje. Vacio si la deuda no genera "
                + "intereses", nullable = true, example = "1.5")
        BigDecimal tasaInteresMensual,
        @Schema(description = "La mora de hoy: lo que crecieron las cuotas vencidas. Cero sin tasa", example = "4.20")
        BigDecimal interesMora,
        @Schema(description = "Lo que se paga hoy para saldar la deuda: el saldo mas la mora. No resta el descuento "
                + "por pronto pago, que va aparte", example = "100.45")
        BigDecimal totalHoy,
        @Schema(description = "La mora que se condona si se paga toda la deuda hoy, durante su campana (contrato "
                + "§7.1). Vacio sin descuento", nullable = true, example = "9400")
        BigDecimal descuentoDisponible,
        @Schema(description = "Hasta cuando vale el descuento: el fin de la campana. Vacio si no tiene fin o no hay "
                + "descuento", nullable = true, example = "2026-11-03")
        LocalDate descuentoHasta,
        @Schema(description = "El deudor no tiene un correo valido: no le llegan el codigo ni los recordatorios por "
                + "correo", example = "false")
        boolean sinCorreoValido,
        @Schema(description = "Si el ultimo correo al deudor no salio: por que, cuando, y si su direccion ya tiene "
                + "problemas. Vacio si el ultimo salio", nullable = true)
        CorreoFallido correoFallido,
        @Schema(description = "La CAE del convenio vigente, guardada al aceptarlo (D.S. 43 de 2012): el costo del "
                + "credito en un ano, con todo incluido, en %. Vacio si la deuda no esta en convenio", nullable = true,
                example = "18.00")
        BigDecimal cae,
        @Schema(description = "La empresa que la cobra esta suspendida: por ahora no se paga ni se repacta, y no se "
                + "le escribe al deudor. Vuelve sola cuando la reactivan", example = "false")
        boolean enPausa
) {

    /** El ultimo correo al deudor no salio. */
    @Schema(description = "El ultimo correo al deudor, que no salio")
    public record CorreoFallido(
            @Schema(description = "Por que, con la direccion tapada",
                    example = "el servidor rechazo la direccion (550 5.1.1 User unknown)")
            String motivo,
            Instant cuando,
            @Schema(description = "El servidor rechazo la direccion tres veces seguidas: ya no se le escribe solo, "
                    + "hasta que una cartera traiga otro correo. La empresa puede reenviar el codigo a mano",
                    example = "false")
            boolean conProblemas
    ) {
        static CorreoFallido de(Debtor deudor) {
            return deudor.getEmailFailedAt() == null ? null
                    : new CorreoFallido(deudor.getEmailFailure(), deudor.getEmailFailedAt(), deudor.correoConProblemas());
        }
    }

    /** El descuento por pronto pago que tiene hoy la deuda, y hasta cuando vale. */
    public record Oferta(BigDecimal monto, LocalDate hasta) {}

    /** Por que el deudor no reconoce la deuda. */
    @Schema(name = "DisputaDeDeuda", description = "La disputa abierta de una deuda")
    public record Disputa(
            @Schema(description = "no_reconoce, ya_pagada, monto_incorrecto u otro", example = "ya_pagada")
            String motivo,
            @Schema(nullable = true, example = "Pague agosto en la oficina el 10 de septiembre") String detalle,
            Instant desde
    ) {}

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private static BigDecimal suma(List<Installment> cuotas, Installment.Status estado) {
        return cuotas.stream().filter(c -> c.getStatus() == estado).map(Installment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Con las cuotas de la deuda: de ellas salen el saldo y el avance. */
    public static DebtSummaryResponse from(Debt deuda, List<Installment> cuotas) {
        return from(deuda, cuotas, null, null);
    }

    /** Para la empresa, ademas, cuando se le envio el codigo al deudor. */
    public static DebtSummaryResponse from(Debt deuda, List<Installment> cuotas, Instant codigoEnviado) {
        return from(deuda, cuotas, codigoEnviado, null);
    }

    public static DebtSummaryResponse from(Debt deuda, List<Installment> cuotas, Instant codigoEnviado,
                                           Disputa disputa) {
        return from(deuda, cuotas, codigoEnviado, disputa, BigDecimal.ZERO);
    }

    /** Con la mora de hoy, que calcula {@code Intereses}. */
    public static DebtSummaryResponse from(Debt deuda, List<Installment> cuotas, Instant codigoEnviado,
                                           Disputa disputa, BigDecimal interesMora) {
        return from(deuda, cuotas, codigoEnviado, disputa, interesMora, null);
    }

    /** Con la mora de hoy y el descuento por pronto pago, si la deuda lo tiene. */
    public static DebtSummaryResponse from(Debt deuda, List<Installment> cuotas, Instant codigoEnviado,
                                           Disputa disputa, BigDecimal interesMora, Oferta oferta) {
        return from(deuda, cuotas, codigoEnviado, disputa, interesMora, oferta, null);
    }

    /** Y la CAE del convenio vigente, que se informa solo si la deuda esta en convenio. */
    public static DebtSummaryResponse from(Debt deuda, List<Installment> cuotas, Instant codigoEnviado,
                                           Disputa disputa, BigDecimal interesMora, Oferta oferta, BigDecimal cae) {
        BigDecimal saldo = suma(cuotas, Installment.Status.pending);
        BigDecimal mora = interesMora == null ? BigDecimal.ZERO : interesMora;
        //  Lo pagado es lo que se pago por DataBridge. No sale de restar el saldo
        //  al monto original: cuando el acreedor actualiza la deuda, su monto ya
        //  viene descontado de lo que se pago aca.
        BigDecimal pagado = suma(cuotas, Installment.Status.paid);
        boolean conConvenio = (deuda.getStatus() == Debt.Status.repacted || deuda.getStatus() == Debt.Status.paid)
                && cuotas.stream().anyMatch(c -> c.getStatus() != Installment.Status.void_ && c.enConvenio());
        //  En convenio el avance se cuenta sobre las cuotas del plan: una cuota
        //  aparte (un mes que el acreedor informo despues) no es parte de el.
        List<Installment> delAvance = cuotas.stream()
                .filter(c -> c.getStatus() != Installment.Status.void_)
                .filter(c -> !conConvenio || c.enConvenio())
                .toList();
        int pagadas = (int) delAvance.stream().filter(c -> c.getStatus() == Installment.Status.paid).count();
        int vigentes = delAvance.size();
        LocalDate hoy = LocalDate.now(CHILE);
        int vencidas = (int) cuotas.stream()
                .filter(c -> c.getStatus() == Installment.Status.pending && c.getDueDate().isBefore(hoy)).count();
        return new DebtSummaryResponse(
                deuda.getId(),
                deuda.getExternalId(),
                deuda.getCreditor().getTradeName(),
                deuda.getCreditor().getRut(),
                deuda.getDebtor().getFullName(),
                deuda.getDebtor().getRut(),
                deuda.getConcept(),
                deuda.getCurrency(),
                deuda.getOriginalAmount(),
                saldo,
                pagado,
                deuda.getStatus(),
                deuda.getUpdatedAt(),
                pagadas,
                vigentes,
                conConvenio,
                vencidas,
                codigoEnviado,
                deuda.getStatus() == Debt.Status.disputed ? disputa : null,
                deuda.getInterestRate(),
                mora,
                saldo.add(mora),
                oferta == null ? null : oferta.monto(),
                oferta == null ? null : oferta.hasta(),
                deuda.getDebtor().getEmail() == null,
                CorreoFallido.de(deuda.getDebtor()),
                //  Solo en convenio: una deuda reabierta o retirada no informa la CAE de un convenio que ya no corre.
                conConvenio ? cae : null,
                deuda.congelada());
    }
}
