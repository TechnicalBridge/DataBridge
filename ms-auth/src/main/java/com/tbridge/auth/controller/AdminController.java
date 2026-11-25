package com.tbridge.auth.controller;

import com.tbridge.auth.config.OpenApiConfig;
import com.tbridge.auth.dto.request.NuevaPersonaRequest;
import com.tbridge.auth.dto.request.CorregirPersonaRequest;
import com.tbridge.auth.dto.response.AccesoResponse;
import com.tbridge.auth.dto.response.AccesosDeHoyResponse;
import com.tbridge.auth.dto.response.AccionResponse;
import com.tbridge.auth.dto.response.BitacoraResponse;
import com.tbridge.auth.dto.response.EstadoResponse;
import com.tbridge.auth.dto.response.IngresoDeDeudorResponse;
import com.tbridge.auth.dto.response.PersonaResponse;
import com.tbridge.auth.dto.response.ReglasResponse;
import com.tbridge.auth.dto.response.SesionAbiertaResponse;
import com.tbridge.auth.dto.response.SesionCerradaResponse;
import com.tbridge.auth.exception.ApiError;
import com.tbridge.auth.model.AccessLog;
import com.tbridge.auth.security.JwtPrincipal;
import com.tbridge.auth.service.BitacoraDeAccesos;
import com.tbridge.auth.service.BitacoraService;
import com.tbridge.auth.service.PersonalService;
import com.tbridge.auth.service.ReglasService;
import com.tbridge.auth.service.SesionesDelPersonal;
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
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Lo que el panel de administracion de DataBridge le pide a ms-auth: las
 * personas con cuenta, y la bitacora de acceso.
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
    private final BitacoraDeAccesos accesos;
    private final BitacoraService bitacora;
    private final SesionesDelPersonal sesiones;

    public AdminController(PersonalService personal, ReglasService reglas, BitacoraDeAccesos accesos,
                           BitacoraService bitacora, SesionesDelPersonal sesiones) {
        this.personal = personal;
        this.reglas = reglas;
        this.accesos = accesos;
        this.bitacora = bitacora;
        this.sesiones = sesiones;
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

    @PatchMapping("/personal/{id}")
    @Operation(summary = "Corregir nombre o correo sin cambiar rol ni empresa",
            description = "Si cambia el correo se revocan las sesiones y los enlaces y códigos pendientes del anterior.")
    public EntityModel<PersonaResponse> corregirPersona(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody CorregirPersonaRequest pedido) {
        return conEnlaces(personal.corregir(id, pedido, admin), admin);
    }

    // ------------------------------------------------------------------
    //  La bitacora de acceso (#141)
    // ------------------------------------------------------------------

    @GetMapping("/accesos")
    @Operation(summary = "La bitacora de acceso, por pagina",
            description = "Cada intento de entrar y cada pedido de codigo o enlace, lo mas reciente primero. Va en "
                    + "`_embedded.accesos` (sin `_embedded` si la pagina viene vacia), con `page` y los enlaces "
                    + "`first`, `prev`, `next` y `last`. Nada identifica a la persona: el RUT va a medias, y no "
                    + "salen ni correos ni IP. Los dias son de Chile y quedan incluidos.")
    @ApiResponse(responseCode = "200", description = "Una pagina de la bitacora")
    @ApiResponse(responseCode = "400", description = "Una fecha, un metodo o un resultado que no existe, o 'hasta' "
            + "antes de 'desde'", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PagedModel<AccesoResponse> accesos(
            @Parameter(description = "Desde ese dia, incluido", example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Hasta ese dia, incluido", example = "2026-10-27")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @Parameter(description = "Solo de un metodo") @RequestParam(required = false) AccessLog.Method metodo,
            @Parameter(description = "Solo con un resultado") @RequestParam(required = false)
            AccessLog.Outcome resultado,
            @Parameter(description = "La pagina, desde 0") @RequestParam(defaultValue = "0") int pagina,
            @Parameter(description = "Cuantas por pagina: 50 si no se dice, nunca mas de 100")
            @RequestParam(defaultValue = "50") int porPagina) {
        Page<AccesoResponse> encontrados = accesos.buscar(desde, hasta, metodo, resultado, pagina, porPagina);
        int tamano = encontrados.getSize();
        List<Link> enlaces = new ArrayList<>();
        enlaces.add(paginaDeAccesos(desde, hasta, metodo, resultado, encontrados.getNumber(), tamano, "self"));
        enlaces.add(paginaDeAccesos(desde, hasta, metodo, resultado, 0, tamano, "first"));
        if (encontrados.hasPrevious()) {
            enlaces.add(paginaDeAccesos(desde, hasta, metodo, resultado, encontrados.getNumber() - 1, tamano, "prev"));
        }
        if (encontrados.hasNext()) {
            enlaces.add(paginaDeAccesos(desde, hasta, metodo, resultado, encontrados.getNumber() + 1, tamano, "next"));
        }
        enlaces.add(paginaDeAccesos(desde, hasta, metodo, resultado,
                Math.max(encontrados.getTotalPages() - 1, 0), tamano, "last"));
        return PagedModel.of(encontrados.getContent(), new PagedModel.PageMetadata(tamano, encontrados.getNumber(),
                encontrados.getTotalElements(), encontrados.getTotalPages()), enlaces);
    }

    @GetMapping("/deudores/{rut}/accesos")
    @Operation(summary = "Los pedidos de codigo y los intentos de entrar de un deudor, para su ficha",
            description = "Los ultimos 50, lo mas reciente primero: si se le envio el codigo o el enlace, si "
                    + "entro o si se le rechazo. Nunca el codigo, su huella ni la IP. La ficha la abre ms-debt, "
                    + "que la anota en la bitacora del panel (#434).")
    @ApiResponse(responseCode = "200", description = "Sus pedidos e intentos; vacio si no tiene")
    @ApiResponse(responseCode = "400", description = "El RUT no es valido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<IngresoDeDeudorResponse> accesosDeDeudor(@PathVariable String rut) {
        return accesos.deUnDeudor(rut);
    }

    @GetMapping("/accesos/hoy")
    @Operation(summary = "Los totales de hoy de la bitacora de acceso",
            description = "Cuantas veces se entro, cuantos intentos se rechazaron y cuantos codigos o enlaces "
                    + "salieron hoy (dia de Chile), y el detalle por resultado.")
    @ApiResponse(responseCode = "200", description = "Los totales del dia")
    public AccesosDeHoyResponse accesosDeHoy() {
        return accesos.hoy();
    }

    @GetMapping("/bitacora")
    @Operation(summary = "La bitacora de acciones administrativas de ms-auth",
            description = "Altas y cambios de acceso, filtrados y paginados. Usa los mismos campos que las bitacoras "
                    + "de ms-debt y ms-payments para que el portal pueda reunirlas.")
    public PagedModel<BitacoraResponse> bitacora(
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String accion,
            @RequestParam(required = false) String empresa,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "25") int porPagina) {
        Page<BitacoraResponse> encontrados = bitacora.buscar(persona, accion, empresa, desde, hasta, pagina, porPagina);
        return PagedModel.of(encontrados.getContent(), new PagedModel.PageMetadata(encontrados.getSize(),
                encontrados.getNumber(), encontrados.getTotalElements(), encontrados.getTotalPages()));
    }

    // ------------------------------------------------------------------
    //  Las sesiones abiertas del personal (#439)
    // ------------------------------------------------------------------

    @GetMapping("/sesiones")
    @Operation(summary = "Las sesiones abiertas del personal",
            description = "De las personas de las empresas y de los admin de DataBridge: quien, de que empresa, desde "
                    + "cuando, su ultima renovacion y hasta cuando puede renovarla sin volver a entrar. La renovada "
                    + "hace menos primero, en `_embedded.sesiones` (sin `_embedded` si no hay ninguna), por paginas "
                    + "(`pagina`, `porPagina`, hasta 50). Sin IP, huellas ni llaves. Las de los deudores no salen.")
    @ApiResponse(responseCode = "200", description = "Una pagina de sesiones abiertas")
    @ApiResponse(responseCode = "400", description = "Una pagina negativa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PagedModel<SesionAbiertaResponse> sesiones(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin,
            @Parameter(description = "La pagina, desde 0") @RequestParam(defaultValue = "0") int pagina,
            @Parameter(description = "Cuantas por pagina: 20 si no se dice, nunca mas de 50")
            @RequestParam(defaultValue = "20") int porPagina) {
        Page<SesionAbiertaResponse> abiertas = sesiones.abiertas(pagina, porPagina, admin);
        return PagedModel.of(abiertas.getContent(), new PagedModel.PageMetadata(abiertas.getSize(),
                abiertas.getNumber(), abiertas.getTotalElements(), abiertas.getTotalPages()));
    }

    @PostMapping("/sesiones/{familia}/revocar")
    @Operation(summary = "Cerrar a distancia una sesion del personal",
            description = "Revoca su familia de llaves de renovacion: la siguiente renovacion responde 401 y la persona "
                    + "tiene que volver a entrar. El JWT que ya tiene no se revoca (#428): su acceso termina a mas "
                    + "tardar en `accesoHasta`, cuando vence. Queda en la bitacora del panel como `sesion_cerrada`.")
    @ApiResponse(responseCode = "200", description = "La sesion, cerrada")
    @ApiResponse(responseCode = "404", description = "No hay ninguna sesion del personal con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "La sesion ya estaba cerrada o vencida",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public SesionCerradaResponse cerrarSesion(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal admin, @PathVariable String familia) {
        return sesiones.cerrar(familia, admin);
    }

    private static Link paginaDeAccesos(LocalDate desde, LocalDate hasta, AccessLog.Method metodo,
                                        AccessLog.Outcome resultado, int pagina, int porPagina, String rel) {
        return linkTo(methodOn(AdminController.class).accesos(desde, hasta, metodo, resultado, pagina, porPagina))
                .withRel(rel);
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
