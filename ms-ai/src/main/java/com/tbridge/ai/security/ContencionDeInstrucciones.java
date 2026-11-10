package com.tbridge.ai.security;

import com.tbridge.ai.util.Valor;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Contención frente a inyección de instrucciones y fuga de datos.
 *
 * <p>El asistente solo habla de las deudas del deudor autenticado. Antes de
 * llamar al LLM se revisan todos los mensajes del deudor: si alguno intenta
 * cambiar el rol del asistente, sacarle las instrucciones internas o pedir
 * datos de un tercero, responde una negativa fija del motor local. Después del
 * LLM se revisa la respuesta: si repite el prompt del sistema, una credencial o
 * un RUT ajeno, se reemplaza por la misma negativa.
 *
 * <p>La revisión de entrada ignora los mensajes del asistente: son salida del
 * modelo y, si se escanearan, la propia negativa envenenaría los turnos
 * siguientes. La respuesta del LLM sí se filtra, porque es justamente donde
 * ocurriría una fuga.
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

    /** Una consulta de datos que menciona un RUT, o un RUT seguido de "de otro". */
    private static final Pattern PIDE_DATOS_POR_RUT = Pattern.compile(
            "(dame|muestra|muestrame|consulta|consultame|necesito|quiero|revela|revelame|entrega|entregame|"
                    + "saldo|datos|deuda|deudas|contacto|correo|telefono|informacion|historial|pago|pagos)"
                    + ".{0,80}(rut|\\d{1,2}\\.\\d{3}\\.\\d{3}-[\\dk]|\\d{7,8}-[\\dk])"
                    + "|(rut|\\d{1,2}\\.\\d{3}\\.\\d{3}-[\\dk]|\\d{7,8}-[\\dk])"
                    + ".{0,40}(de|del|sobre)\\s+(otro|otra|tercer|ajen|un|una)");

    /** Referencias explícitas a otra persona o a datos de terceros. */
    private static final Pattern TERCERO = Pattern.compile(
            "(otro|otra|tercer|ajen)[a-z]*\\s+(deudor|persona|cliente|usuario|rut|cuenta)"
                    + "|(datos|saldo|deuda|contacto|correo|telefono|informacion)\\s+(de|del|sobre)\\s+"
                    + "(otro|otra|tercer|ajen)");

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
            Pattern.compile("(muestra|muestrame|revela|revelame|dame|ensena|entregame|imprime|escribe)[a-z]*"
                    + "[^.]{0,40}(tus|las|mis) (instrucciones|directivas|reglas|claves|credenciales|configuracion)"),
            Pattern.compile("(primeras|ultimas) \\d{1,4} palabras"),
            Pattern.compile("(directivas|instrucciones|reglas|configuracion) (internas?|del sistema|de sistema)"),
            Pattern.compile("(tus|las|mis) (claves|credenciales|contrasenas|tokens)"),
            Pattern.compile("api[ _-]?key|clave de api|groq_api_key|gemini_api_key|openai_api_key"),
            Pattern.compile("(soy|eres|actua como|actuar como|finge ser|pretende ser) "
                    + "(un |una |el |la )?(administrador|admin|desarrollador|root|superusuario)"),
            Pattern.compile("jailbreak|dan mode|developer mode"),
            Pattern.compile("(repite|repetir|resume|resumir|traduce|traducir|copia|copiar)[a-z]*"
                    + "[^.]{0,40}(tus|las|mis) (instrucciones|directivas|reglas|prompt)"),
            Pattern.compile("(que|cuales) (son|fueron) (tus|las|mis) (instrucciones|directivas|claves|credenciales)"));

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

    /** La negativa si algún mensaje del deudor intenta manipular el asistente. */
    public static Optional<String> revisionDeEntrada(List<Map<String, Object>> historia) {
        if (historia == null) {
            return Optional.empty();
        }
        for (Map<String, Object> mensaje : historia) {
            if (mensaje == null || "assistant".equals(mensaje.get("role"))) {
                continue;
            }
            Object contenido = Valor.primero(mensaje.get("content"), mensaje.get("message"));
            if (contenido != null && esIntentoDeManipulacion(String.valueOf(contenido))) {
                return Optional.of(RECHAZO);
            }
        }
        return Optional.empty();
    }

    /** Si el texto pide cambiar el rol, sacar instrucciones o consultar a un tercero. */
    public static boolean esIntentoDeManipulacion(String texto) {
        String normalizado = normalizar(texto);
        if (normalizado.isEmpty()) {
            return false;
        }
        return MANIPULACION.stream().anyMatch(patron -> patron.matcher(normalizado).find())
                || PIDE_DATOS_POR_RUT.matcher(normalizado).find()
                || TERCERO.matcher(normalizado).find();
    }

    /** La respuesta tal cual, o la negativa si repite el prompt, una credencial o un RUT. */
    public static String protegerRespuesta(String respuesta) {
        if (respuesta == null || respuesta.isBlank()) {
            return RECHAZO;
        }
        String normalizado = normalizar(respuesta);
        boolean fuga = FUGA.stream().anyMatch(patron -> patron.matcher(normalizado).find())
                || CREDENCIALES.stream().anyMatch(patron -> patron.matcher(normalizado).find())
                || RUT.matcher(normalizado).find();
        return fuga ? RECHAZO : respuesta;
    }

    private static String normalizar(String texto) {
        String sinTildes = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFKD)
                .replaceAll("[^\\x00-\\x7F]", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
