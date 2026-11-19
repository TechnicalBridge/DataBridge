package com.tbridge.payments.controller;

import com.tbridge.payments.config.OpenApiConfig;
import com.tbridge.payments.dto.request.UfManualRequest;
import com.tbridge.payments.dto.response.EstadoResponse;
import com.tbridge.payments.dto.response.OperacionResponse;
import com.tbridge.payments.dto.response.PagoAdminDetalleResponse;
import com.tbridge.payments.dto.response.PagoAdminResponse;
import com.tbridge.payments.dto.response.PagoVerificadoResponse;
import com.tbridge.payments.dto.response.PagoDeDeudorResponse;
import com.tbridge.payments.dto.response.ReglasResponse;
import com.tbridge.payments.dto.response.UfResponse;
import com.tbridge.payments.exception.ApiError;
import com.tbridge.payments.security.JwtPrincipal;
import com.tbridge.payments.service.OperacionService;
import com.tbridge.payments.service.PagosAdminService;
import com.tbridge.payments.service.PagosDelDeudorService;
import com.tbridge.payments.service.ReglasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.hateoas.PagedModel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Lo que el panel de administracion de DataBridge le pide a ms-payments.
 *
 * <p>Todo lo que cuelga de {@code /api/admin} es solo para el admin de
 * DataBridge: lo exige {@code SecurityConfig}, no cada metodo, para que un
 * endpoint nuevo no pueda quedar abierto por olvido. El panel muestra totales
 * por empresa, nunca los datos de un deudor.
 */
@RestController
@RequestMapping("/api/admin/payments")
@Tag(name = "Panel de administracion", description = "Solo para el admin de DataBridge")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class AdminController {

    private final ReglasService reglas;
    private final OperacionService operacion;
    private final PagosDelDeudorService pagosDelDeudor;
    private final PagosAdminService pagosAdmin;

    public AdminController(ReglasService reglas, OperacionService operacion, PagosDelDeudorService pagosDelDeudor,
                           PagosAdminService pagosAdmin) {
        this.pagosAdmin = pagosAdmin;
        this.pagosDelDeudor = pagosDelDeudor;
        this.reglas = reglas;
        this.operacion = operacion;
    }

    // ------------------------------------------------------------------
    //  La operacion de los pagos (#139)
    // ------------------------------------------------------------------

    @GetMapping("/operacion")
    @Operation(summary = "Como esta la operacion de los pagos",
            description = "Cuantos pagos estan abiertos (y cuantos llevan mas de una hora), cuantos vencieron en las "
                    + "ultimas 24 horas y cuantos duplicados falta devolver; la UF de hoy en Chile, si ya esta; y "
                    + "los avisos de pago a ms-debt que no se han entregado. Solo totales: ningun dato de un deudor.")
    @ApiResponse(responseCode = "200", description = "La operacion de los pagos")
    public OperacionResponse operacion() {
        return operacion.operacion();
    }

    @PostMapping("/avisos/{id}/reintentar")
    @Operation(summary = "Volver a poner en cola un aviso de pago a ms-debt",
            description = "Queda pendiente para ahora y el despachador lo entrega en su pasada siguiente (cada 15 "
                    + "segundos). ms-debt abona una sola vez aunque el aviso le llegue dos. Queda anotado quien lo hizo.")
    @ApiResponse(responseCode = "200", description = "El aviso, ya en cola")
    @ApiResponse(responseCode = "404", description = "No hay ningun aviso con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Ese aviso ya se entrego",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public OperacionResponse.Aviso reintentarAviso(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin, @PathVariable Long id) {
        return operacion.reintentarAviso(id, admin.email());
    }

    @PostMapping("/cobros/{id}/liberar")
    @Operation(summary = "Liberar un cobro que sigue abierto hace mas de una hora",
            description = "Para un cobro trabado, por ejemplo uno de Mercado Pago en proceso que deja la cuota "
                    + "bloqueada por horas (#453). Antes se le pregunta a la pasarela: si ya lo cobro, queda pagado. "
                    + "Si no, queda vencido y la cuota se puede volver a pagar; si la pasarela lo aprueba despues, "
                    + "se registra igual. Queda anotado quien lo libero.")
    @ApiResponse(responseCode = "200", description = "Como quedo el cobro: vencido, o pagado si la pasarela ya lo cobro")
    @ApiResponse(responseCode = "404", description = "No hay ningun cobro con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "El cobro ya no esta abierto, o lleva menos de una hora",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public OperacionResponse.CobroLiberado liberarCobro(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin, @PathVariable Long id) {
        return operacion.liberarCobro(id, admin.email());
    }

    @GetMapping("/pagos")
    @Operation(summary = "Los pagos de todas las empresas",
            description = "El mas nuevo primero, con filtros por estado, pasarela, empresa (RUT del acreedor) y dias "
                    + "de Chile (incluidos). Con `revisar=true`, solo los abiertos hace mas de una hora y los "
                    + "duplicados. Va en `_embedded.pagos`, con paginas (`pagina`, `porPagina`, hasta 50). Sin datos "
                    + "del deudor (#435).")
    @ApiResponse(responseCode = "200", description = "Una pagina de pagos")
    @ApiResponse(responseCode = "400", description = "Un estado, una pasarela o un RUT que no existe, o fechas al reves",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PagedModel<PagoAdminResponse> pagos(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String pasarela,
            @RequestParam(required = false) String empresa,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "false") boolean revisar,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int porPagina) {
        Page<PagoAdminResponse> encontrados = pagosAdmin.buscar(estado, pasarela, empresa, desde, hasta, revisar,
                pagina, porPagina);
        return PagedModel.of(encontrados.getContent(), new PagedModel.PageMetadata(encontrados.getSize(),
                encontrados.getNumber(), encontrados.getTotalElements(), encontrados.getTotalPages()));
    }

    @GetMapping("/pagos/{id}")
    @Operation(summary = "Un pago con su historia", description = "Lo que le paso, en orden, y la deuda que paga (#435).")
    @ApiResponse(responseCode = "200", description = "El pago")
    @ApiResponse(responseCode = "404", description = "No hay ningun pago con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PagoAdminDetalleResponse pago(@PathVariable Long id) {
        return pagosAdmin.detalle(id);
    }

    @PostMapping("/pagos/{id}/verificar")
    @Operation(summary = "Volver a preguntarle a la pasarela por un pago abierto",
            description = "Lo mismo que hace la consulta periodica, a pedido. Si la pasarela ya cobro, se confirma "
                    + "por el camino de siempre, una sola vez; si sigue en curso, queda abierto. Queda en la "
                    + "bitacora del panel como `pago_verificado` (#435).")
    @ApiResponse(responseCode = "200", description = "Como quedo el pago")
    @ApiResponse(responseCode = "404", description = "No hay ningun pago con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "El pago ya no esta abierto",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PagoVerificadoResponse verificarPago(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
            @PathVariable Long id) {
        return pagosAdmin.verificar(id, admin.email());
    }

    @GetMapping("/deudores/{rut}/pagos")
    @Operation(summary = "Los pagos de un deudor, para su ficha",
            description = "Los ultimos 100, el mas reciente primero, en todas las empresas: pasarela, estado, monto "
                    + "y cuotas. Sin ids ni tokens de la pasarela. La ficha la abre ms-debt, que la anota en la "
                    + "bitacora del panel (#434).")
    @ApiResponse(responseCode = "200", description = "Sus pagos; vacio si no tiene")
    @ApiResponse(responseCode = "400", description = "El RUT no es valido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<PagoDeDeudorResponse> pagosDeDeudor(@PathVariable String rut) {
        return pagosDelDeudor.pagos(rut);
    }

    @PostMapping("/uf")
    @Operation(summary = "Cargar a mano la UF de un dia",
            description = "Para cuando el Banco Central no la entrego. Queda como la UF de ese dia y es la que usan "
                    + "los cobros en UF. Si ese dia ya tenia valor, lo reemplaza. Queda anotado quien la cargo.")
    @ApiResponse(responseCode = "200", description = "La UF guardada")
    @ApiResponse(responseCode = "400", description = "Falta el dia o el valor, o no es positivo",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UfResponse cargarUf(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
                               @Valid @RequestBody UfManualRequest pedido) {
        return operacion.cargarUf(pedido.dia(), pedido.valor(), admin.email());
    }

    @GetMapping("/reglas")
    @Operation(summary = "Las reglas de pago que rigen",
            description = "Que pasarelas cobran de verdad y cuales quedan simuladas, en cuanto vence un cobro en "
                    + "cada una y de donde sale la UF. De las credenciales dice solo si estan. Cada regla dice de "
                    + "que variable sale. Se leen al arrancar: cambiar una pide reiniciar el servicio.")
    @ApiResponse(responseCode = "200", description = "Las reglas, por grupo")
    public ReglasResponse reglas() {
        return reglas.reglas();
    }

    @GetMapping("/estado")
    @Operation(summary = "Si ms-payments responde",
            description = "La portada del panel lo llama para mostrar que servicios estan arriba.")
    @ApiResponse(responseCode = "200", description = "El servicio responde")
    @ApiResponse(responseCode = "401", description = "Sin sesion",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "La sesion no es de un admin de DataBridge",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EstadoResponse estado() {
        return new EstadoResponse("ms-payments", true);
    }
}
