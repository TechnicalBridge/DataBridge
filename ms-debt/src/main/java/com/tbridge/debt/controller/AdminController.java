package com.tbridge.debt.controller;

import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.request.NuevaEmpresaRequest;
import com.tbridge.debt.dto.response.AccionResponse;
import com.tbridge.debt.dto.response.AvisosResponse;
import com.tbridge.debt.dto.response.ComisionesResponse;
import com.tbridge.debt.dto.response.EmpresaResponse;
import com.tbridge.debt.dto.response.EstadoResponse;
import com.tbridge.debt.dto.response.ReglasResponse;
import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.service.AvisosService;
import com.tbridge.debt.service.ComisionService;
import com.tbridge.debt.service.EmpresaService;
import com.tbridge.debt.service.HorarioDeContacto;
import com.tbridge.debt.service.ReglasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

/**
 * Lo que el panel de administracion de DataBridge le pide a ms-debt.
 *
 * <p>Todo lo que cuelga de {@code /api/admin} es solo para el admin de
 * DataBridge: lo exige {@code SecurityConfig}, no cada metodo, para que un
 * endpoint nuevo no pueda quedar abierto por olvido. El panel muestra totales
 * por empresa, nunca los datos de un deudor.
 */
@RestController
@RequestMapping("/api/admin/debt")
@Tag(name = "Panel de administracion", description = "Solo para el admin de DataBridge")
@SecurityRequirement(name = OpenApiConfig.JWT)
@ApiResponse(responseCode = "401", description = "Sin sesion",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "403", description = "La sesion no es de un admin de DataBridge",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
public class AdminController {

    private final EmpresaService empresas;
    private final ReglasService reglas;
    private final ComisionService comisiones;
    private final AvisosService avisos;

    public AdminController(EmpresaService empresas, ReglasService reglas, ComisionService comisiones,
                           AvisosService avisos) {
        this.empresas = empresas;
        this.reglas = reglas;
        this.comisiones = comisiones;
        this.avisos = avisos;
    }

    // ------------------------------------------------------------------
    //  Los avisos a las empresas que no se entregan (#139)
    // ------------------------------------------------------------------

    /** Cuantos avisos se volvieron a poner en cola. */
    public record Reintentados(@Schema(example = "21") int reintentados) {
    }

    @GetMapping("/avisos")
    @Operation(summary = "Los avisos a las empresas que no se han entregado",
            description = "Pendientes (esperan su proximo intento) y fallidos (se rindieron despues del sexto), "
                    + "agrupados por destinatario, con sus intentos y su ultimo error. Sin el contenido de ningun "
                    + "aviso, que lleva datos de la deuda, y del destinatario solo el esquema, el host y el puerto.")
    @ApiResponse(responseCode = "200", description = "Los avisos sin entregar")
    public AvisosResponse avisos() {
        return avisos.sinEntregar();
    }

    @PostMapping("/avisos/{id}/reintentar")
    @Operation(summary = "Volver a poner en cola un aviso",
            description = "Queda pendiente para ahora y el despachador lo entrega en su pasada siguiente (cada 15 "
                    + "segundos). Si vuelve a fallar despues del sexto intento, queda otra vez como fallido. Queda "
                    + "anotado quien lo hizo, en la historia de la empresa.")
    @ApiResponse(responseCode = "200", description = "El aviso, ya en cola")
    @ApiResponse(responseCode = "404", description = "No hay ningun aviso con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Ese aviso ya se entrego",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AvisosResponse.Aviso reintentar(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
                                           @PathVariable Long id) {
        return avisos.reintentar(id, admin.email());
    }

    @PostMapping("/avisos/destinos/{suscripcion}/reintentar")
    @Operation(summary = "Volver a poner en cola los avisos fallidos de un destinatario",
            description = "Para cuando la empresa vuelve a responder despues de una caida. Los pendientes siguen su "
                    + "curso. Queda anotado quien lo hizo y cuantos fueron.")
    @ApiResponse(responseCode = "200", description = "Cuantos se volvieron a poner en cola")
    @ApiResponse(responseCode = "404", description = "Ese destinatario no tiene avisos fallidos",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public Reintentados reintentarDestino(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
                                          @PathVariable Long suscripcion) {
        return new Reintentados(avisos.reintentarDestino(suscripcion, admin.email()));
    }

    @GetMapping("/comisiones")
    @Operation(summary = "Lo que DataBridge le cobra a cada empresa en un mes",
            description = "El modelo de negocio (#200), por empresa usuaria: lo recuperado en el mes en pesos (G), "
                    + "el porcentaje de su tramo y la comision P(G) = G * p(G). La paga quien cargo la deuda: la "
                    + "agencia, o el acreedor que cobra directo; un pago nunca se cuenta dos veces.")
    @ApiResponse(responseCode = "200", description = "Cada empresa con pagos en el mes, y el total")
    public ComisionesResponse comisiones(
            @Parameter(description = "El mes, como 2026-10. Sin el, el de hoy", example = "2026-10")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return comisiones.deTodas(mes);
    }

    @GetMapping("/estado")
    @Operation(summary = "Si ms-debt responde",
            description = "La portada del panel lo llama para mostrar que servicios estan arriba.")
    @ApiResponse(responseCode = "200", description = "El servicio responde")
    public EstadoResponse estado() {
        return new EstadoResponse("ms-debt", true);
    }

    @GetMapping("/reglas")
    @Operation(summary = "Las reglas de cobranza y de contacto que rigen",
            description = "La mora minima, la tasa maxima, el aviso antes de una cuota, el horario y el limite de "
                    + "contacto de la ley, y los feriados de este ano y del siguiente: los mismos que usan las "
                    + "campanas. Cada una dice de que variable sale. Se leen al arrancar: cambiar una pide reiniciar.")
    @ApiResponse(responseCode = "200", description = "Las reglas, por grupo")
    public ReglasResponse reglas() {
        return reglas.reglas(LocalDate.now(HorarioDeContacto.CHILE));
    }

    @GetMapping("/empresas")
    @Operation(summary = "Las empresas registradas",
            description = "Con su tipo, sus mandatos y los totales de su cartera: cuantas deudas y cuanto falta por "
                    + "cobrar. Ningun dato de un deudor. Van en `_embedded.empresas`.")
    @ApiResponse(responseCode = "200", description = "Las empresas, por nombre")
    public CollectionModel<EntityModel<EmpresaResponse>> empresas() {
        return CollectionModel.of(empresas.listar().stream().map(AdminController::conEnlaces).toList(),
                linkTo(AdminController.class).slash("empresas").withSelfRel());
    }

    @GetMapping("/empresas/{rut}")
    @Operation(summary = "Una empresa", description = "La misma ficha de la lista, para una sola.")
    @ApiResponse(responseCode = "200", description = "La empresa")
    @ApiResponse(responseCode = "404", description = "No hay ninguna empresa con ese RUT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<EmpresaResponse> empresa(@PathVariable String rut) {
        return conEnlaces(empresas.ficha(rut));
    }

    @PostMapping("/empresas")
    @Operation(summary = "Dar de alta una empresa usuaria",
            description = "Una agencia de cobranza, o un acreedor que cobra directo. Los acreedores de una agencia no "
                    + "se dan de alta aqui: los presenta ella con el mandato. Sus personas se crean despues, en "
                    + "ms-auth. Queda anotado quien la dio de alta.")
    @ApiResponse(responseCode = "201", description = "La empresa, ya registrada")
    @ApiResponse(responseCode = "400", description = "Falta un dato, o el RUT no es valido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Ya hay una empresa con ese RUT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<EntityModel<EmpresaResponse>> crear(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
            @Valid @RequestBody NuevaEmpresaRequest pedido) {
        EntityModel<EmpresaResponse> creada = conEnlaces(empresas.crear(pedido, admin.email()));
        return ResponseEntity.created(creada.getRequiredLink(IanaLinkRelations.SELF).toUri()).body(creada);
    }

    @GetMapping("/empresas/{rut}/historial")
    @Operation(summary = "Lo que se hizo sobre una empresa desde el panel",
            description = "Quien la dio de alta y cuando. Lo que se hizo con sus personas lo anota ms-auth. "
                    + "Va en `_embedded.acciones`, lo mas reciente primero.")
    @ApiResponse(responseCode = "200", description = "Las acciones")
    @ApiResponse(responseCode = "404", description = "No hay ninguna empresa con ese RUT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CollectionModel<AccionResponse> historial(@PathVariable String rut) {
        return CollectionModel.of(empresas.historial(rut),
                linkTo(AdminController.class).slash("empresas").slash(rut).slash("historial").withSelfRel());
    }

    private static EntityModel<EmpresaResponse> conEnlaces(EmpresaResponse empresa) {
        return EntityModel.of(empresa,
                linkTo(AdminController.class).slash("empresas").slash(empresa.rut()).withSelfRel(),
                linkTo(AdminController.class).slash("empresas").slash(empresa.rut()).slash("historial")
                        .withRel("historial"));
    }
}
