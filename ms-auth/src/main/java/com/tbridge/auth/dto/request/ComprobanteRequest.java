package com.tbridge.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Lo que ms-debt manda cuando aplica un pago confirmado: el comprobante para
 * el correo del deudor (#541). Los montos y las fechas vienen ya escritos,
 * como en el PDF: ms-auth no sabe de deudas, solo escribe el correo.
 */
@Schema(description = "El comprobante de un pago, para el correo del deudor")
public record ComprobanteRequest(
        @Schema(example = "felipe.rojas@correo.cl")
        @NotBlank(message = "Falta el correo") @Email(message = "El correo no es valido")
        String correo,

        @Schema(description = "El folio del pago en DataBridge", example = "57")
        @NotBlank(message = "Falta el folio") @Size(max = 20)
        String folio,

        @Schema(description = "Lo que se pago, en la moneda de la deuda", example = "$346.666")
        @NotBlank(message = "Falta el monto") @Size(max = 40)
        String monto,

        @Schema(description = "En UF, cuantos pesos fueron", nullable = true,
                example = "Equivale a $1.540.000, con la UF de ese día ($39.876,54)")
        @Size(max = 200)
        String nota,

        @Schema(example = "Patrimonio Inmuebles")
        @NotBlank(message = "Falta el acreedor") @Size(max = 200)
        String acreedor,

        @Schema(description = "El detalle, en el orden en que se muestra")
        @NotEmpty(message = "Falta el detalle") @Size(max = 12)
        List<@Valid Fila> filas,

        @Schema(description = "Si el pago dejo la deuda pagada por completo")
        boolean saldada,

        @Schema(description = "Lo que falta por pagar, si no quedo pagada", nullable = true, example = "$693.332")
        @Size(max = 40)
        String queda,

        @Schema(description = "El comprobante en PDF y, si quedo pagada, el certificado")
        @NotEmpty(message = "Falta el comprobante") @Size(max = 2)
        List<@Valid Adjunto> adjuntos
) {

    public record Fila(
            @Schema(example = "Medio de pago") @NotBlank @Size(max = 60) String nombre,
            @Schema(example = "Webpay, operación wp-9f31c2") @NotBlank @Size(max = 300) String valor
    ) {
    }

    /** Un PDF. Solo se aceptan estos dos nombres, y el contenido tiene que ser un PDF. */
    public record Adjunto(
            @Schema(example = "comprobante-57.pdf")
            @NotBlank @Pattern(regexp = "(comprobante|certificado)-\\d{1,12}\\.pdf",
                    message = "El adjunto se llama comprobante-N.pdf o certificado-N.pdf")
            String nombre,

            @Schema(description = "El PDF, en base64", type = "string", format = "byte")
            @NotNull @Size(min = 5, max = 2_000_000)
            byte[] contenido
    ) {

        /** Si de verdad es un PDF: empieza con {@code %PDF-}. */
        public boolean esPdf() {
            return contenido != null && contenido.length >= 5 && contenido[0] == '%' && contenido[1] == 'P'
                    && contenido[2] == 'D' && contenido[3] == 'F' && contenido[4] == '-';
        }
    }
}
