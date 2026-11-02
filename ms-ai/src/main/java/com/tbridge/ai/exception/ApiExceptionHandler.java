package com.tbridge.ai.exception;

import jakarta.servlet.ServletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Todos los errores salen con la misma forma que en los otros servicios,
 * {@link ApiError}, que es la que el portal sabe leer.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DeudasException.class)
    public ResponseEntity<ApiError> deuda(DeudasException ex) {
        return responder(HttpStatusCode.valueOf(ex.status()), ex.getMessage());
    }

    /** Los que lanza el propio asistente, con su texto. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> conMotivo(ResponseStatusException ex) {
        String motivo = ex.getReason() != null ? ex.getReason() : mensajePara(ex.getStatusCode());
        return responder(ex.getStatusCode(), motivo);
    }

    /** JSON mal escrito, o un campo que trae otro tipo. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> cuerpoIlegible(HttpMessageNotReadableException ex) {
        return responder(HttpStatus.BAD_REQUEST,
                "El cuerpo de la peticion no es JSON valido o trae un campo con otro tipo");
    }

    /** Lo que Spring ya sabe responder con su propio codigo: 404, 405, 415. */
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
        log.error("category=unexpected_error");
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno");
    }

    private static String mensajePara(HttpStatusCode codigo) {
        return switch (codigo.value()) {
            case 401 -> "No autorizado";
            case 404 -> "No existe esa ruta";
            case 405 -> "Esa ruta no acepta ese metodo";
            case 406 -> "No se puede responder en el formato pedido";
            case 415 -> "Tipo de contenido no soportado";
            default -> "La peticion no se pudo procesar";
        };
    }

    private static ResponseEntity<ApiError> responder(HttpStatusCode codigo, String mensaje) {
        return ResponseEntity.status(codigo).body(new ApiError(mensaje));
    }
}
