package com.tbridge.debt.controller;

import com.tbridge.debt.events.PagoConfirmado;
import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.request.EmitirClaveRequest;
import com.tbridge.debt.dto.response.AvanceResponse;
import com.tbridge.debt.dto.response.ClaveEmitidaResponse;
import com.tbridge.debt.dto.response.ContactosResponse;
import com.tbridge.debt.dto.response.CorreoDelDeudorResponse;
import com.tbridge.debt.dto.response.DebtSnapshotResponse;
import com.tbridge.debt.dto.response.OkResponse;
import com.tbridge.debt.dto.response.OrganizacionResponse;
import com.tbridge.debt.dto.response.RecordatoriosResponse;
import com.tbridge.debt.service.ApiKeyService;
import com.tbridge.debt.service.CampanaAvanceService;
import com.tbridge.debt.service.DebtService;
import com.tbridge.debt.service.EmpresaService;
import com.tbridge.debt.service.ContactoService;
import com.tbridge.debt.service.RecordatorioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Lo que un servicio hermano le pregunta o le avisa a ms-debt. No es publico:
 * va detras de la clave interna y el gateway no lo expone.
 */
@RestController
@RequestMapping("/internal")
@Tag(name = "Interno", description = "Para los otros servicios y operaciones, con la clave interna")
@SecurityRequirement(name = OpenApiConfig.CLAVE_INTERNA)
@ApiResponse(responseCode = "401", description = "Clave interna invalida",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
public class InternalController {

    private final DebtService debts;
    private final CampanaAvanceService avances;
    private final ApiKeyService claves;
    private final RecordatorioService recordatorios;
    private final ContactoService contactos;
    private final EmpresaService empresas;
    private final String internalKey;

    public InternalController(DebtService debts, CampanaAvanceService avances, ApiKeyService claves,
                              RecordatorioService recordatorios, ContactoService contactos,
                              EmpresaService empresas,
                              @Value("${app.internal-key}") String internalKey) {
        this.debts = debts;
        this.avances = avances;
        this.claves = claves;
        this.recordatorios = recordatorios;
        this.contactos = contactos;
        this.empresas = empresas;
        this.internalKey = internalKey;
    }

    @GetMapping("/debts/{id}")
    @Operation(summary = "Cuanto se debe y a quien (para ms-payments)",
            description = "ms-payments cobra este monto y no el que manda el navegador.")
    @ApiResponse(responseCode = "200", description = "El monto a cobrar")
    @ApiResponse(responseCode = "400", description = "Una cuota de otra deuda, o repetida",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "La deuda o la cuota no existen",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Sin saldo, una cuota que no esta pendiente, "
            + "o cuotas que no son las que vencen primero",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public DebtSnapshotResponse deuda(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @PathVariable Long id,
            @Parameter(description = "Las cuotas a pagar, que tienen que ser las que vencen primero. "
                    + "Sin ellas, todo el saldo") @RequestParam(required = false) List<Long> installmentIds
    ) {
        exigirClave(clave);
        return debts.snapshotInterno(id, installmentIds);
    }

    @GetMapping("/deudores/{rut}/correo")
    @Operation(summary = "El correo registrado de un deudor (para ms-auth)",
            description = "El que registro el acreedor en su cartera. ms-auth manda el enlace de acceso solo ahi, "
                    + "nunca al correo que alguien escriba en el formulario.")
    @ApiResponse(responseCode = "200", description = "El correo")
    @ApiResponse(responseCode = "404", description = "Ese RUT no esta en cartera o no tiene correo",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CorreoDelDeudorResponse correoDelDeudor(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @PathVariable String rut
    ) {
        exigirClave(clave);
        return debts.correoDelDeudor(rut).map(CorreoDelDeudorResponse::new)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Sin correo registrado"));
    }

    @GetMapping("/cartera/{rut}")
    @Operation(summary = "Las deudas de la cartera de una empresa (para ms-payments)",
            description = "Las mismas que la empresa ve en el portal: donde es acreedora y las que entrego como "
                    + "agencia. ms-payments las usa para mostrarle sus pagos para devolver.")
    @ApiResponse(responseCode = "200", description = "Los id de las deudas; vacio si la empresa no esta registrada")
    public List<Long> cartera(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @PathVariable String rut
    ) {
        exigirClave(clave);
        return debts.carteraInterna(rut);
    }

    @PostMapping("/claves")
    @Operation(summary = "Emitir una clave de API",
            description = "Para una organizacion registrada. Se devuelve una sola vez: en la base queda solo su huella.")
    @ApiResponse(responseCode = "200", description = "La clave")
    @ApiResponse(responseCode = "404", description = "No hay organizacion con ese RUT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ClaveEmitidaResponse clave(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @Valid @RequestBody EmitirClaveRequest pedido
    ) {
        exigirClave(clave);
        return claves.emitirPara(pedido.rut(), pedido.nombreOPorOmision());
    }

    @PostMapping("/campanas/avance")
    @Operation(summary = "Publicar ahora el avance de las campanas",
            description = "Sin esperar la corrida de las 8:00. Lo usan operaciones y las pruebas.")
    @ApiResponse(responseCode = "200", description = "El avance de cada campana en curso")
    public AvanceResponse avance(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave) {
        exigirClave(clave);
        return avances.publicarTodas();
    }

    @PostMapping("/campanas/contactos")
    @Operation(summary = "Mandar ahora los toques de las campanas",
            description = """
                    Sin esperar la pasada de cada 15 minutos. Con `ahora`, como si fuera ese momento en Chile: \
                    sirve para la demo y las pruebas, porque una cadencia de 45 dias no se puede esperar. Los \
                    toques quedan fechados a esa hora, y se respetan igual el horario, los feriados y el limite \
                    de dos por semana.""")
    @ApiResponse(responseCode = "200", description = "Cuantos salieron, cuantos quedaron para despues, y si era horario")
    public ContactosResponse contactar(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @Parameter(description = "El momento, hora de Chile, si no es ahora", example = "2026-10-08T10:00:00")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime ahora,
            @Parameter(description = "Solo esta campana, por su id externo: las demas no se tocan",
                    example = "APX-CMP-8")
            @RequestParam(required = false) String campana
    ) {
        exigirClave(clave);
        return contactos.contactar(ahora == null ? LocalDateTime.now(ZoneId.of("America/Santiago")) : ahora,
                campana == null || campana.isBlank() ? null : campana.trim());
    }

    @PostMapping("/recordatorios")
    @Operation(summary = "Mandar ahora los recordatorios de cuota",
            description = """
                    Sin esperar la pasada de las 9:00. Con `hoy`, como si fuera ese dia: sirve para la demo y las                     pruebas, porque una cuota que vence en tres dias no se puede esperar.""")
    @ApiResponse(responseCode = "200", description = "Cuantos salieron y cuantos no")
    public RecordatoriosResponse recordar(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @Parameter(description = "El dia desde el que se cuenta, si no es hoy", example = "2026-10-17")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hoy
    ) {
        exigirClave(clave);
        return recordatorios.enviar(hoy == null ? LocalDate.now(ZoneId.of("America/Santiago")) : hoy);
    }

    @PostMapping("/events/pago-confirmado")
    @Operation(summary = "El aviso de un pago concretado (desde ms-payments)",
            description = "El camino por HTTP; con RabbitMQ el mismo aviso llega por la cola. Un aviso repetido no abona dos veces.")
    @ApiResponse(responseCode = "200", description = "Recibido")
    public OkResponse pagoConfirmado(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @RequestBody PagoConfirmado aviso
    ) {
        exigirClave(clave);
        debts.onPagoConfirmado(aviso);
        return OkResponse.OK;
    }

    @GetMapping("/organizaciones/{rut}")
    @Operation(summary = "Si hay una empresa con ese RUT (para ms-auth)",
            description = "ms-auth lo pregunta antes de darle una persona a una empresa: el personal vive alla y la "
                    + "empresa aca, unidos por el RUT.")
    @ApiResponse(responseCode = "200", description = "La empresa")
    @ApiResponse(responseCode = "404", description = "No hay ninguna empresa con ese RUT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public OrganizacionResponse organizacion(
            @Parameter(hidden = true) @RequestHeader(value = "X-Internal-Key", required = false) String clave,
            @PathVariable String rut
    ) {
        exigirClave(clave);
        return empresas.buscar(rut)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay ninguna empresa con ese RUT"));
    }

    private void exigirClave(String clave) {
        if (clave == null || !clave.equals(internalKey)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Clave interna invalida");
        }
    }
}
