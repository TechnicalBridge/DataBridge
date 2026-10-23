package com.tbridge.auth.controller;

import com.tbridge.auth.config.OpenApiConfig;
import com.tbridge.auth.dto.request.NuevaPersonaRequest;
import com.tbridge.auth.dto.response.AccionResponse;
import com.tbridge.auth.dto.response.EstadoResponse;
import com.tbridge.auth.dto.response.PersonaResponse;
import com.tbridge.auth.dto.response.ReglasResponse;
import com.tbridge.auth.exception.ApiError;
import com.tbridge.auth.security.JwtPrincipal;
import com.tbridge.auth.service.PersonalService;
import com.tbridge.auth.service.ReglasService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

/**
 * Lo que el panel de administracion de DataBridge le pide a ms-auth: las
 * personas con cuenta.
 *
 * <p>Todo lo que cuelga de {@code /api/admin} es solo para el admin de
 * DataBridge: lo exige {@code SecurityConfig}, no cada metodo, para que un
 * endpoint nuevo no pueda quedar abierto por olvido.
 */
@RestController
@RequestMapping("/api/admin/auth")
@Tag(name = "Panel de administracion", description = "Solo para el admin de DataBridge")
@SecurityRequirement(name = OpenApiConfig.JWT)
@ApiResponse(responseCode = "401", description = "Sin sesion",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "403", description = "La sesion no es de un admin de DataBridge",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
public class AdminController {

    private final PersonalService personal;
    private final ReglasService reglas;

    public AdminController(PersonalService personal, ReglasService reglas) {
        this.personal = personal;
        this.reglas = reglas;
    }

    @GetMapping("/estado")
    @Operation(summary = "Si ms-auth responde",
            description = "La portada del panel lo llama para mostrar que servicios estan arriba.")
    @ApiResponse(responseCode = "200", description = "El servicio responde")
    public EstadoResponse estado() {
        return new EstadoResponse("ms-auth", true);
    }

    @GetMapping("/reglas")
    @Operation(summary = "Las reglas de acceso y de correo que rigen",
            description = "Cuanto duran el codigo, el enlace, la invitacion y la sesion, y por donde salen los correos "
                    + "y en que modo. Cada una dice de que variable sale. Ninguna clave ni direccion completa. Se leen "
                    + "al arrancar: cambiar una pide reiniciar el servicio.")
    @ApiResponse(responseCode = "200", description = "Las reglas, por grupo")
    public ReglasResponse reglas() {
        return reglas.reglas();
    }

    // ------------------------------------------------------------------
    //  Las personas de una empresa
    // ------------------------------------------------------------------

    @GetMapping("/empresas/{rut}/personal")
    @Operation(summary = "Las personas de una empresa",
            description = "Van en `_embedded.personas`. Cada una trae el enlace `deshabilitar` o `habilitar`, "
                    + "segun lo que se pueda hacer con ella.")
    @ApiResponse(responseCode = "200", description = "Las personas, por nombre")
    @ApiResponse(responseCode = "400", description = "RUT no valido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CollectionModel<EntityModel<PersonaResponse>> personalDeEmpresa(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin, @PathVariable String rut) {
        return coleccion(personal.deEmpresa(rut), admin, "empresas", rut, "personal");
    }

    @PostMapping("/empresas/{rut}/personal")
    @Operation(summary = "Dar de alta a una persona de una empresa",
            description = "Queda como `usuario` de esa empresa: por esta ruta no se puede crear un admin. La empresa "
                    + "tiene que existir en ms-debt. A la persona le llega su invitacion al correo, con un enlace "
                    + "de un solo uso. Queda anotado quien la dio de alta.")
    @ApiResponse(responseCode = "201", description = "La persona, ya con cuenta")
    @ApiResponse(responseCode = "400", description = "Falta un dato, o el RUT o el correo no son validos",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No hay ninguna empresa con ese RUT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Ese correo ya tiene una cuenta, o la empresa esta suspendida",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "ms-debt no responde: no se pudo comprobar la empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<EntityModel<PersonaResponse>> crearUsuario(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
            @PathVariable String rut, @Valid @RequestBody NuevaPersonaRequest pedido) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conEnlaces(personal.crearUsuario(rut, pedido, admin), admin));
    }

    @GetMapping("/empresas/{rut}/historial")
    @Operation(summary = "Lo que se hizo con las personas de una empresa",
            description = "Altas, y accesos quitados y devueltos, con quien lo hizo y cuando. La alta de la empresa "
                    + "misma la anota ms-debt. Va en `_embedded.acciones`, lo mas reciente primero.")
    @ApiResponse(responseCode = "200", description = "Las acciones")
    public CollectionModel<AccionResponse> historialDeEmpresa(@PathVariable String rut) {
        return CollectionModel.of(personal.historialDeEmpresa(rut),
                linkTo(AdminController.class).slash("empresas").slash(rut).slash("historial").withSelfRel());
    }

    // ------------------------------------------------------------------
    //  Los admin de DataBridge
    // ------------------------------------------------------------------

    @GetMapping("/admins")
    @Operation(summary = "Los admin de DataBridge", description = "Van en `_embedded.personas`.")
    @ApiResponse(responseCode = "200", description = "Los admin, por nombre")
    public CollectionModel<EntityModel<PersonaResponse>> admins(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin) {
        return coleccion(personal.admins(), admin, "admins");
    }

    @PostMapping("/admins")
    @Operation(summary = "Dar de alta a otro admin de DataBridge",
            description = "Un admin no tiene empresa. Un correo que ya es de una persona de una empresa no puede "
                    + "pasar a ser admin. Le llega su invitacion al correo, y queda anotado quien lo dio de alta.")
    @ApiResponse(responseCode = "201", description = "El admin, ya con cuenta")
    @ApiResponse(responseCode = "400", description = "Falta un dato, o el correo no es valido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Ese correo ya tiene una cuenta",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<EntityModel<PersonaResponse>> crearAdmin(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
            @Valid @RequestBody NuevaPersonaRequest pedido) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conEnlaces(personal.crearAdmin(pedido, admin), admin));
    }

    @GetMapping("/admins/historial")
    @Operation(summary = "Lo que se hizo con los admin de DataBridge",
            description = "Va en `_embedded.acciones`, lo mas reciente primero.")
    @ApiResponse(responseCode = "200", description = "Las acciones")
    public CollectionModel<AccionResponse> historialDeAdmins() {
        return CollectionModel.of(personal.historialDeAdmins(),
                linkTo(AdminController.class).slash("admins").slash("historial").withSelfRel());
    }

    // ------------------------------------------------------------------
    //  Quitar y devolver el acceso
    // ------------------------------------------------------------------

    @PostMapping("/personal/{id}/deshabilitar")
    @Operation(summary = "Quitarle el acceso a una persona",
            description = "No le llegan mas enlaces, los que tenia sin abrir dejan de servir y sus sesiones se "
                    + "cierran. La cuenta no se borra. Un admin no se puede deshabilitar a si mismo.")
    @ApiResponse(responseCode = "200", description = "La persona, sin acceso")
    @ApiResponse(responseCode = "404", description = "No hay ninguna persona con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Es el admin de la sesion",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<PersonaResponse> deshabilitar(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin, @PathVariable Long id) {
        return conEnlaces(personal.deshabilitar(id, admin), admin);
    }

    @PostMapping("/personal/{id}/habilitar")
    @Operation(summary = "Devolverle el acceso a una persona",
            description = "Puede volver a pedir su enlace. No se le manda nada.")
    @ApiResponse(responseCode = "200", description = "La persona, con acceso")
    @ApiResponse(responseCode = "404", description = "No hay ninguna persona con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<PersonaResponse> habilitar(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin, @PathVariable Long id) {
        return conEnlaces(personal.habilitar(id, admin), admin);
    }

    private static CollectionModel<EntityModel<PersonaResponse>> coleccion(
            List<PersonaResponse> personas, JwtPrincipal admin, String... ruta) {
        var propio = linkTo(AdminController.class);
        for (String tramo : ruta) {
            propio = propio.slash(tramo);
        }
        return CollectionModel.of(personas.stream().map(p -> conEnlaces(p, admin)).toList(), propio.withSelfRel());
    }

    /** Lo que se puede hacer con cada persona. El admin de la sesion no trae como deshabilitarse. */
    private static EntityModel<PersonaResponse> conEnlaces(PersonaResponse persona, JwtPrincipal admin) {
        EntityModel<PersonaResponse> modelo = EntityModel.of(persona);
        boolean esElMismo = admin != null && String.valueOf(persona.id()).equals(admin.id());
        if (!persona.habilitada()) {
            modelo.add(linkTo(AdminController.class).slash("personal").slash(persona.id()).slash("habilitar")
                    .withRel("habilitar"));
        } else if (!esElMismo) {
            modelo.add(linkTo(AdminController.class).slash("personal").slash(persona.id()).slash("deshabilitar")
                    .withRel("deshabilitar"));
        }
        return modelo;
    }
}
