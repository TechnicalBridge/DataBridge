package com.tbridge.auth.controller;

import com.tbridge.auth.assembler.SesionModelAssembler;
import com.tbridge.auth.config.OpenApiConfig;
import com.tbridge.auth.dto.request.AccesoRequest;
import com.tbridge.auth.dto.request.EnlaceRequest;
import com.tbridge.auth.dto.request.VerifyRequest;
import com.tbridge.auth.dto.response.EnlaceResponse;
import com.tbridge.auth.dto.response.MeResponse;
import com.tbridge.auth.dto.response.SesionResponse;
import com.tbridge.auth.dto.response.UsuarioResponse;
import com.tbridge.auth.service.AuthService;
import com.tbridge.auth.exception.ApiError;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.security.JwtPrincipal;
import com.tbridge.auth.security.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * Las puertas del portal.
 *
 * <p>La de adelante es el codigo. El enlace queda como respaldo.
 *
 * <p><b>Dos piezas por sesion, y viajan separadas.</b> El JWT va en el cuerpo:
 * el portal lo guarda en memoria y lo manda en cada peticion. La llave de
 * renovacion va en una cookie que JavaScript no puede leer, limitada a
 * {@code /api/auth}, y solo sirve para pedir el JWT siguiente. Asi, un script
 * inyectado en la pagina puede usar la sesion mientras la pagina este abierta,
 * pero no puede llevarsela: lo unico que dura esta fuera de su alcance.
 */
@RestController
@Tag(name = "Sesion", description = "Entrar, renovar y cerrar la sesion del portal")
public class AuthController {

    static final String COOKIE = "tb_renovacion";

    private final AuthService auth;
    private final JwtService jwt;
    private final SesionModelAssembler enlaces;
    private final boolean cookieSegura;

    public AuthController(AuthService auth, JwtService jwt, SesionModelAssembler enlaces,
                          @Value("${app.cookie-secure:false}") boolean cookieSegura) {
        this.auth = auth;
        this.jwt = jwt;
        this.enlaces = enlaces;
        this.cookieSegura = cookieSegura;
    }

    @PostMapping("/api/auth/acceso")
    @Operation(summary = "Entrar con RUT o correo y codigo",
            description = """
                    El deudor usa RUT; el personal usa correo. El codigo sirve una vez y se agota a los \
                    5 intentos. El solicitado dura MAGIC_TTL_MINUTES (15 por omision); el de campana \
                    dura CODE_TTL_HOURS (24 por omision). \
                    El mismo mensaje responde a "ese RUT no tiene codigo" y a "el codigo no corresponde", \
                    para que nadie pueda averiguar que RUT tienen deuda.

                    Devuelve el JWT en el cuerpo y deja la llave de renovacion en la cookie `tb_renovacion`.""")
    @ApiResponse(responseCode = "200", description = "Sesion abierta")
    @ApiResponse(responseCode = "400", description = "Falta un dato o el RUT no es valido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "El codigo no corresponde o expiro",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "Codigo agotado, o demasiados intentos desde el mismo origen",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<EntityModel<SesionResponse>> acceso(@Valid @RequestBody AccesoRequest pedido,
                                                              HttpServletRequest peticion) {
        return responder(pedido.correo() == null || pedido.correo().isBlank()
                ? auth.entrarConCodigo(pedido.rut(), pedido.codigo(), origen(peticion))
                : auth.entrarConCodigoDePersonal(pedido.correo(), pedido.codigo(), origen(peticion)));
    }

    @PostMapping("/api/auth/codigo")
    @Operation(summary = "Pedir solo un codigo de acceso",
            description = "Al correo registrado del deudor o del personal habilitado. No incluye enlace y no revela "
                    + "si existe la identidad. El codigo sirve una vez y dura 15 minutos.")
    @ApiResponse(responseCode = "202", description = "Solicitud recibida, sin revelar el destinatario")
    public ResponseEntity<EnlaceResponse> codigo(@Valid @RequestBody EnlaceRequest pedido, HttpServletRequest peticion) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(auth.pedirCodigo(pedido.rut(), pedido.correo(), origen(peticion)));
    }

    @PostMapping("/api/auth/enlace")
    @Operation(summary = "Pedir codigo y enlace de respaldo en un correo",
            description = """
                    El respaldo para quien no recibio su codigo, en cualquiera de los tres portales. \
                    Codigo y enlace comparten un uso y duran 15 minutos. La respuesta es la misma \
                    exista o no el correo.""")
    @ApiResponse(responseCode = "202", description = "Solicitud de codigo y enlace recibida")
    @ApiResponse(responseCode = "400", description = "Correo o RUT no validos",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<EnlaceResponse> enlace(@Valid @RequestBody EnlaceRequest pedido, HttpServletRequest peticion) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(auth.pedirEnlace(pedido.rut(), pedido.correo(), origen(peticion)));
    }

    @PostMapping("/api/auth/verify")
    @Operation(summary = "Canjear el enlace del correo por una sesion")
    @ApiResponse(responseCode = "200", description = "Sesion abierta")
    @ApiResponse(responseCode = "401", description = "Enlace invalido, usado o vencido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<EntityModel<SesionResponse>> verify(@Valid @RequestBody VerifyRequest pedido,
                                                              HttpServletRequest peticion) {
        return responder(auth.entrarConEnlace(pedido.token(), origen(peticion)));
    }

    /**
     * Cambiar la llave de renovacion por un JWT nuevo.
     *
     * <p>Si la llave no sirve, ademas del error se borra la cookie: si no, el
     * navegador la seguiria mandando en cada recarga de la pagina.
     */
    @PostMapping("/api/auth/refresh")
    @Operation(summary = "Renovar la sesion",
            description = """
                    Cambia la llave de la cookie `tb_renovacion` por un JWT nuevo y la llave siguiente. \
                    Cada llave sirve una vez: si una llave ya usada vuelve a aparecer pasados 30 segundos, \
                    alguien mas la tiene, y se revoca la sesion completa. Dentro de esos 30 segundos es \
                    otra pestana del mismo navegador, y se responde 409 para que reintente.""")
    @Parameter(name = COOKIE, in = ParameterIn.COOKIE, description = "La llave de renovacion", required = true)
    @ApiResponse(responseCode = "200", description = "JWT nuevo, y la llave siguiente en la cookie")
    @ApiResponse(responseCode = "401", description = "No hay sesion, o se revoco o vencio",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Otra pestana la acaba de renovar: reintentar",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<?> refresh(@Parameter(hidden = true) @CookieValue(name = COOKIE, required = false) String llave,
                                     HttpServletRequest peticion) {
        if (llave == null || llave.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError("No hay una sesion que renovar"));
        }
        try {
            return responder(auth.renovar(llave, origen(peticion)));
        } catch (ApiException e) {
            ResponseEntity.BodyBuilder respuesta = ResponseEntity.status(e.getStatus());
            //  409 es "otra pestana la acaba de renovar": la cookie ya tiene la
            //  llave nueva y hay que dejarla donde esta.
            if (e.getStatus() != HttpStatus.CONFLICT) {
                respuesta.header(HttpHeaders.SET_COOKIE, borrarCookie().toString());
            }
            return respuesta.body(new ApiError(e.getMessage()));
        }
    }

    @PostMapping("/api/auth/logout")
    @Operation(summary = "Cerrar sesion",
            description = "Revoca la sesion en el servidor, no solo en el navegador, y borra la cookie.")
    @Parameter(name = COOKIE, in = ParameterIn.COOKIE, description = "La llave de renovacion")
    @ApiResponse(responseCode = "204", description = "Sesion cerrada")
    public ResponseEntity<Void> logout(@Parameter(hidden = true) @CookieValue(name = COOKIE, required = false) String llave) {
        if (llave != null && !llave.isBlank()) {
            auth.cerrar(llave);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, borrarCookie().toString()).build();
    }

    @GetMapping("/api/me")
    @Operation(summary = "Quien soy", description = "El dueno del JWT con que se pregunta.")
    @SecurityRequirement(name = OpenApiConfig.JWT)
    @ApiResponse(responseCode = "200", description = "El usuario de la sesion")
    @ApiResponse(responseCode = "401", description = "Sin JWT, o vencido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<MeResponse> me(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return enlaces.toModel(new MeResponse(UsuarioResponse.de(user)));
    }

    private ResponseEntity<EntityModel<SesionResponse>> responder(AuthService.Sesion sesion) {
        //  expiraEnSegundos: para que el portal sepa cuando le toca renovar, en
        //  vez de enterarse por un 401 en medio de algo.
        SesionResponse cuerpo = new SesionResponse(sesion.token(), sesion.user(), jwt.vida().toSeconds());
        Duration vida = Duration.between(Instant.now(), sesion.llave().venceEn());
        ResponseCookie cookie = cookie(sesion.llave().valor(), vida.isNegative() ? Duration.ZERO : vida);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(enlaces.toModel(cuerpo));
    }

    /**
     * La cookie de la llave.
     *
     * <ul>
     *   <li>{@code HttpOnly}: ningun script de la pagina la puede leer.</li>
     *   <li>{@code SameSite=Strict}: otro sitio no puede hacer que el navegador
     *       la mande, asi que no puede renovar ni cerrar la sesion de nadie.</li>
     *   <li>{@code Path=/api/auth}: solo viaja a donde sirve. Los otros
     *       servicios nunca la ven.</li>
     *   <li>{@code Secure} segun {@code COOKIE_SECURE}: con HTTPS tiene que ir
     *       encendido. Esta apagado por omision porque todo este proyecto se
     *       sirve por HTTP, y una cookie Secure sobre HTTP el navegador la
     *       descarta sin avisar: la sesion se caeria cada quince minutos.</li>
     * </ul>
     */
    private ResponseCookie cookie(String valor, Duration vida) {
        return ResponseCookie.from(COOKIE, valor)
                .httpOnly(true)
                .secure(cookieSegura)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(vida)
                .build();
    }

    private ResponseCookie borrarCookie() {
        return cookie("", Duration.ZERO);
    }

    /**
     * De donde viene el intento, para contar intentos por origen. De la IP se
     * guarda su huella, nunca la IP.
     *
     * <p>Es la direccion del cliente, no la del gateway: el servicio lee
     * X-Forwarded-For ({@code server.forward-headers-strategy=framework}), que
     * el gateway solo deja pasar desde sus proxies de confianza.
     */
    private static String origen(HttpServletRequest peticion) {
        return peticion.getRemoteAddr();
    }
}
