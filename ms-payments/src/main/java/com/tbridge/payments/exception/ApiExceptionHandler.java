package com.tbridge.payments.exception;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.ServletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Todos los errores salen con la misma forma, {@link ApiError}, y con el codigo
 * que les corresponde.
 *
 * <p>Antes solo se distinguia {@link ApiException}: todo lo demas caia en
 * "Error interno" con codigo 500, aunque la culpa fuera de quien llamaba. Un
 * JSON mal escrito o una ruta que no existe respondian 500 y dejaban una traza
 * completa en el log, como si el servicio se hubiera caido.
 *
 * <p>Va con la precedencia mas baja a proposito. Un servicio que necesita otra
 * forma para algunos errores —ms-debt en el contrato de integracion— declara
 * su propio manejador y gana; si este fuera primero, su captura de
 * {@link Exception} se quedaria con todo.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> deNegocio(ApiException ex) {
        return responder(ex.getStatus(), ex.getMessage());
    }

    /** Un cuerpo con {@code @Valid} que no cumple: se dice el primer campo que falla. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> cuerpoInvalido(MethodArgumentNotValidException ex) {
        String mensaje = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("La petición no es válida");
        return responder(HttpStatus.BAD_REQUEST, mensaje);
    }

    /** Un parametro suelto con restricciones ({@code @Min} en un {@code @RequestParam}) que no cumple. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> parametroInvalido(HandlerMethodValidationException ex) {
        String mensaje = ex.getAllErrors().stream()
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse("La petición no es válida");
        return responder(HttpStatus.BAD_REQUEST, mensaje);
    }

    /**
     * JSON mal escrito, un campo que trae otro tipo (texto donde va un numero),
     * o un campo que no existe en una peticion que no los tolera.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> cuerpoIlegible(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof UnrecognizedPropertyException desconocido) {
            return responder(HttpStatus.BAD_REQUEST,
                    "La petición trae un campo que no existe: '" + desconocido.getPropertyName() + "'");
        }
        return responder(HttpStatus.BAD_REQUEST,
                "El cuerpo de la petición no es JSON válido o trae un campo con otro tipo");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> tipoInvalido(MethodArgumentTypeMismatchException ex) {
        return responder(HttpStatus.BAD_REQUEST, "El parámetro '" + ex.getName() + "' no tiene el formato esperado");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> faltaParametro(MissingServletRequestParameterException ex) {
        return responder(HttpStatus.BAD_REQUEST, "Falta el parámetro '" + ex.getParameterName() + "'");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> faltaArchivo(MissingServletRequestPartException ex) {
        return responder(HttpStatus.BAD_REQUEST, "Falta la parte '" + ex.getRequestPartName() + "' del formulario");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> archivoGrande(MaxUploadSizeExceededException ex) {
        return responder(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo pasa del tamaño máximo permitido");
    }

    /**
     * Lo que Spring ya sabe responder con su propio codigo: una ruta que no
     * existe (404), un metodo que no corresponde (405), un tipo de contenido
     * que no se acepta (415).
     */
    @ExceptionHandler({ServletException.class, ErrorResponseException.class})
    public ResponseEntity<ApiError> deSpring(Exception ex) {
        if (ex instanceof ErrorResponse conCodigo) {
            HttpStatusCode codigo = conCodigo.getStatusCode();
            return responder(codigo, mensajePara(codigo));
        }
        return inesperado(ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> inesperado(Exception ex) {
        log.error("Error no controlado", ex);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno");
    }

    private static String mensajePara(HttpStatusCode codigo) {
        return switch (codigo.value()) {
            case 404 -> "No existe esa ruta";
            case 405 -> "Esa ruta no acepta ese método";
            case 406 -> "No se puede responder en el formato pedido";
            case 415 -> "Tipo de contenido no soportado";
            default -> "La petición no se pudo procesar";
        };
    }

    private static ResponseEntity<ApiError> responder(HttpStatusCode codigo, String mensaje) {
        return ResponseEntity.status(codigo).body(new ApiError(mensaje));
    }
}
