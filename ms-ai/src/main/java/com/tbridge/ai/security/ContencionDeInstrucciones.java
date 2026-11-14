package com.tbridge.ai.security;

import com.tbridge.ai.util.Valor;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contención frente a inyección de instrucciones y fuga de datos.
 *
 * <p>El asistente solo habla de las deudas del deudor autenticado. Antes de
 * llamar al LLM se revisa solo el mensaje nuevo del deudor: si intenta cambiar
 * el rol del asistente, sacarle las instrucciones internas o pedir datos de un
 * tercero, responde una negativa fija del motor local. Los mensajes anteriores
 * que sean ataques se quitan del historial que viaja al modelo, para que un
 * rechazo no bloquee la conversación. Después del LLM se revisa la respuesta:
 * si repite el prompt del sistema, una credencial o un RUT ajeno, se reemplaza
 * por la misma negativa.
 *
 * <p>El RUT de la sesión y los RUT de los acreedores de sus deudas son datos
 * legítimos de la conversación. Solo un RUT fuera de ese conjunto cuenta como
 * fuga o como intento de consultar a un tercero.
 */
public final class ContencionDeInstrucciones {

    /** La negativa única: cortés, dentro del dominio y sin ecos del ataque. */
    public static final String RECHAZO = "No puedo hacer eso. Solo puedo ayudarte con tus propias deudas: "
            + "saldo, cuotas y cómo pagar. No revelo instrucciones internas, claves ni configuración, y no "
            + "entrego datos de otras personas ni de otros RUT. Si quieres, te muestro tu saldo o te explico "
            + "cómo pagar.";

    /** RUT con puntos y guion, o sin puntos y con guion. No confunde montos: exige el guion y el dígito. */
    private static final Pattern RUT = Pattern.compile(
            "(?<!\\d)\\d{1,2}\\.\\d{3}\\.\\d{3}-[\\dk]|(?<!\\d)\\d{7,8}-[\\dk]");

    /** Pedir datos de otra persona. "Otra cuenta" u "otra persona puede pagar" no entran. */
    private static final Pattern TERCERO = Pattern.compile(
            "(datos|saldo|deuda|deudas|contacto|correo|telefono|informacion|historial|cuotas?)"
                    + "\\s+(de|del|sobre)\\s+((un|una|el|la)\\s+)?(otro|otra|tercer|ajen)[a-z]*");

    /** Órdenes de manipulación, extracción del prompt o cambio de rol. */
    private static final List<Pattern> MANIPULACION = List.of(
            Pattern.compile("ignora (todas )?(tus|las|mis) (instrucciones|directivas|reglas)"),
            Pattern.compile("ignore (all )?(previous|prior|the|your) (instructions|prompts?)"),
            Pattern.compile("olvida (todas )?(tus|las|mis) (instrucciones|directivas|reglas)"),
            Pattern.compile("disregard (all )?(previous|prior|the|your) (instructions|prompts?)"),
            Pattern.compile("(entra|entrar|activa|activar|pon|ponte) (en )?modo "
                    + "(depuracion|debug|desarrollador|desarrollo|administrador|root)"),
            Pattern.compile("modo (depuracion|debug|desarrollador|desarrollo|administrador|root)"),
            Pattern.compile("(system|sistema) prompt|prompt (del|de) sistema"),
            Pattern.compile("(dime|muestra|muestrame|revela|revelame|dame|ensena|entregame|imprime|escribe)"
                    + "[a-z]*[^.]{0,40}(tus|tu) "
                    + "(instrucciones|directivas|reglas|claves|credenciales|configuracion|prompt)"),
            Pattern.compile("(primeras|ultimas) \\d{1,4} palabras (de|del) "
                    + "(tus|tu|las|el) (instrucciones|directivas|reglas|prompt|sistema)"),
            Pattern.compile("(directivas|instrucciones|reglas|configuracion) (internas?|del sistema|de sistema)"),
            Pattern.compile("(tus|sus) (claves|credenciales|contrasenas|tokens)"),
            Pattern.compile("(claves|credenciales|contrasenas|tokens) "
                    + "(configuradas|del sistema|de la aplicacion|del asistente|del servicio|de la api)"),
            Pattern.compile("api[ _-]?key|clave de api|groq_api_key|gemini_api_key|openai_api_key"),
            Pattern.compile("(soy|eres|actua como|actuar como|finge ser|pretende ser) "
                    + "(un |una |el |la )?(administrador|admin|desarrollador|root|superusuario)"),
            Pattern.compile("jailbreak|dan mode|developer mode"),
            Pattern.compile("(repite|repetir|resume|resumir|traduce|traducir|copia|copiar)[a-z]*"
                    + "[^.]{0,40}(tus|tu) (instrucciones|directivas|reglas|prompt)"),
            Pattern.compile("(que|cuales) (son|fueron) (tus|sus) "
                    + "(instrucciones|directivas|reglas|claves|credenciales)"));

    /** Fragmentos que solo pueden salir si el modelo repite sus instrucciones. */
    private static final List<Pattern> FUGA = List.of(
            Pattern.compile("eres el asistente de technical bridge"),
            Pattern.compile("ayudas a deudores en chile"),
            Pattern.compile("no inventes montos"),
            Pattern.compile("deudas \\(solo lectura"),
            Pattern.compile("animo detectado en el ultimo mensaje"),
            Pattern.compile("regla de seguridad"),
            Pattern.compile("(system|sistema) prompt|prompt (del|de) sistema"),
            Pattern.compile("(directivas|instrucciones|reglas) internas"),
            Pattern.compile("configuracion interna"));

    /** Marcas de credenciales o secretos que el asistente jamás debe repetir. */
    private static final List<Pattern> CREDENCIALES = List.of(
            Pattern.compile("sk-[a-z0-9_-]{8,}"),
            Pattern.compile("(api[ _-]?key|groq_api_key|gemini_api_key|openai_api_key)\\s*[:=]"),
            Pattern.compile("(bearer|token|password|contrasena|secret|credencial)\\s*[:=]\\s*\\S{6,}"),
            Pattern.compile("aiza[0-9a-z_-]{20,}"),
            Pattern.compile("gh[pousr]_[a-z0-9]{10,}"),
            Pattern.compile("xox[baprs]-[a-z0-9-]{10,}"));

    private ContencionDeInstrucciones() {
    }

    /** Los RUT que la sesión puede mencionar: el suyo y los de sus acreedores. */
    public static Set<String> rutsPermitidos(String rutSesion, List<Map<String, Object>> deudas) {
        Set<String> permitidos = new LinkedHashSet<>();
        agregarRut(permitidos, rutSesion);
        if (deudas != null) {
            for (Map<String, Object> deuda : deudas) {
                if (deuda != null) {
                    agregarRut(permitidos, deuda.get("acreedorRut"));
                }
            }
        }
        return Set.copyOf(permitidos);
    }

    /** La negativa si el mensaje nuevo del deudor intenta manipular el asistente. */
    public static Optional<String> revisionDeEntrada(String mensaje, Collection<String> rutsPermitidos) {
        return esIntentoDeManipulacion(mensaje, rutsPermitidos)
                ? Optional.of(RECHAZO)
                : Optional.empty();
    }

    /** El historial sin los mensajes del deudor que contengan un ataque. */
    public static List<Map<String, Object>> historiaSinAtaques(List<Map<String, Object>> historia,
                                                               Collection<String> rutsPermitidos) {
        if (historia == null) {
            return List.of();
        }
        return historia.stream()
                .filter(Objects::nonNull)
                .filter(mensaje -> "assistant".equals(mensaje.get("role"))
                        || !esIntentoDeManipulacion(textoDe(mensaje), rutsPermitidos))
                .toList();
    }

    /** Si el texto pide cambiar el rol, sacar instrucciones o consultar a un tercero. */
    public static boolean esIntentoDeManipulacion(String texto, Collection<String> rutsPermitidos) {
        String normalizado = normalizar(texto);
        if (normalizado.isEmpty()) {
            return false;
        }
        return contieneRutNoPermitido(normalizado, rutsPermitidos)
                || MANIPULACION.stream().anyMatch(patron -> patron.matcher(normalizado).find())
                || TERCERO.matcher(normalizado).find();
    }

    /** La respuesta tal cual, o la negativa si repite el prompt, una credencial o un RUT ajeno. */
    public static String protegerRespuesta(String respuesta, Collection<String> rutsPermitidos) {
        if (respuesta == null || respuesta.isBlank()) {
            return RECHAZO;
        }
        String normalizado = normalizar(respuesta);
        boolean fuga = FUGA.stream().anyMatch(patron -> patron.matcher(normalizado).find())
                || CREDENCIALES.stream().anyMatch(patron -> patron.matcher(normalizado).find())
                || contieneRutNoPermitido(normalizado, rutsPermitidos);
        return fuga ? RECHAZO : respuesta;
    }

    private static String textoDe(Map<String, Object> mensaje) {
        Object contenido = Valor.primero(mensaje.get("content"), mensaje.get("message"));
        return contenido == null ? "" : String.valueOf(contenido);
    }

    private static void agregarRut(Set<String> permitidos, Object valor) {
        if (valor != null) {
            String canonico = canonizarRut(String.valueOf(valor));
            if (!canonico.isEmpty()) {
                permitidos.add(canonico);
            }
        }
    }

    private static boolean contieneRutNoPermitido(String texto, Collection<String> rutsPermitidos) {
        Set<String> permitidos = new LinkedHashSet<>();
        if (rutsPermitidos != null) {
            for (String rut : rutsPermitidos) {
                String canonico = canonizarRut(rut);
                if (!canonico.isEmpty()) {
                    permitidos.add(canonico);
                }
            }
        }
        Matcher matcher = RUT.matcher(texto);
        while (matcher.find()) {
            if (!permitidos.contains(canonizarRut(matcher.group()))) {
                return true;
            }
        }
        return false;
    }

    private static String canonizarRut(String rut) {
        return rut == null ? "" : rut.toLowerCase(Locale.ROOT).replaceAll("[^0-9k]", "");
    }

    private static String normalizar(String texto) {
        String sinTildes = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFKD)
                .replaceAll("[^\\x00-\\x7F]", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
