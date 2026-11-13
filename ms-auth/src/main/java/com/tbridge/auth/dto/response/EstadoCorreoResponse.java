package com.tbridge.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Como esta saliendo el correo ahora, para la pantalla Correo del panel.
 * Nunca trae una llave ni una direccion entera: de las direcciones del modo
 * de prueba solo dice cuantas son.
 */
@Schema(description = "El estado del correo de ms-auth, sin llaves ni direcciones completas")
public record EstadoCorreoResponse(
        @Schema(description = "brevo, smtp, buzon-de-prueba o ninguno", example = "brevo") String proveedor,
        @Schema(description = "Texto para mostrar", example = "Por la API de Brevo") String descripcion,
        @Schema(description = "Si hay por donde mandar correos") boolean configurado,
        @Schema(description = "Si el modo de prueba esta activo") boolean modoDePrueba,
        @Schema(description = "Cuantas direcciones o dominios reciben directo en modo de prueba") int direccionesPermitidas,
        @Schema(description = "Si lo que no recibe directo va a un buzon de desvio") boolean desvia,
        @Schema(description = "Tope de desvios por dia (CORREO_DESVIO_MAXIMO_POR_DIA); 0 es sin tope") int cupoMaximoPorDia,
        @Schema(description = "Desvios usados hoy, en hora de Chile") int cupoUsadoHoy,
        @Schema(description = "Remitente con la cuenta tapada", example = "DataBridge <da***@gmail.com>") String remitente
) {
}
