package com.tbridge.debt.controller;

import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.request.NuevaEmpresaRequest;
import com.tbridge.debt.dto.response.AccionResponse;
import com.tbridge.debt.dto.response.EmpresaResponse;
import com.tbridge.debt.dto.response.EstadoResponse;
import com.tbridge.debt.dto.response.ReglasResponse;
import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.security.JwtPrincipal;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

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

    public AdminController(EmpresaService empresas, ReglasService reglas) {
        this.empresas = empresas;
        this.reglas = reglas;
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
