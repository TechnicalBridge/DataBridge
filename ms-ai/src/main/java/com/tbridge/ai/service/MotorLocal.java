package com.tbridge.ai.service;

import com.tbridge.ai.dto.response.Animo;
import com.tbridge.ai.util.Valor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * El motor local del asistente: responde sin LLM, con reglas.
 *
 * <p>Lee las deudas tal como las entrega ms-debt ({@code totalHoy}, {@code moneda},
 * {@code estado}, la tasa, la mora y el descuento) y dice lo mismo que el portal.
 * Pesos y UF no se suman entre si: una deuda en UF sumada a una en pesos daria
 * un total que no significa nada.
 *
 * <p>El analisis de sentimiento es por lexico, no un modelo. Alcanza para lo que
 * importa en cobranza: notar cuando alguien esta frustrado, o cuando desconfia
 * de que el mensaje sea real, que es la razon principal por la que la gente no
 * paga lo que si quiere pagar (docs de APOFYX, seccion 10).
 */
public final class MotorLocal {

    static final Set<String> VIGENTES = Set.of("open", "repacted");

    static final Map<String, String> ESTADOS = Map.of(
            "open", "pendiente",
            "repacted", "en convenio de pago",
            "paid", "pagada",
            "withdrawn", "retirada por el acreedor",
            "disputed", "en revision");

    //  Raices, sin tildes: el texto se normaliza antes de buscar.
    private static final List<String> FRUSTRACION = List.of(
            "harto", "chato", "cansad", "enojad", "molest", "rabia", "abuso", "injust", "reclam",
            "no puedo pagar", "no tengo plata", "no me alcanza", "cesante", "sin trabajo", "sin pega",
            "desesperad", "angusti", "me cobran de mas", "no es justo", "pesimo", "horrible");
    private static final List<String> DESCONFIANZA = List.of(
            "estafa", "fraude", "phishing", "es real", "es verdad", "es confiable", "confiar", "sospech",
            "quien eres", "quienes son", "de donde sacaron", "como tienen mis datos", "como consiguieron",
            "me estan cobrando algo que no", "no reconozco", "no es mia", "no es mio");
    private static final List<String> POSITIVO = List.of(
            "gracias", "genial", "perfecto", "excelente", "buenisimo", "bacan", "listo", "super");

    private static final Pattern SIN_PLATA =
            Pattern.compile("no puedo pagar|no tengo plata|no me alcanza|cesante|sin trabajo|sin pega");
    private static final Pattern SALUDO = Pattern.compile("\\b(hola|buenas|hey|saludos|alo)\\b");
    private static final Pattern CUOTAS = Pattern.compile("repact|cuot|plazo|convenio|plan");
    private static final Pattern PAGAR = Pattern.compile("pagar|pago|pasarela|khipu|webpay|mercado");
    private static final Pattern SALDO = Pattern.compile("saldo|deuda|cuanto|debo|a quien");
    private static final Pattern INTERES = Pattern.compile("interes|descuento|rebaja|condon");

    private static final DateTimeFormatter DIA =
            DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es-CL"));

    private MotorLocal() {
    }

    static String normalizar(String texto) {
        String sinTildes = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFKD)
                .replaceAll("[^\\x00-\\x7F]", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("[\\s\\x1C-\\x1F]+", " ").strip();
    }

    /**
     * Frustracion y desconfianza pesan mas que lo positivo: "gracias, pero esto
     * es un abuso" es un reclamo, no un agradecimiento.
     */
    public static Animo sentimiento(String mensaje) {
        String texto = normalizar(mensaje);
        Map<String, List<String>> lexicos = new LinkedHashMap<>();
        lexicos.put("desconfianza", DESCONFIANZA);
        lexicos.put("frustracion", FRUSTRACION);
        lexicos.put("positivo", POSITIVO);
        for (Map.Entry<String, List<String>> lexico : lexicos.entrySet()) {
            int senales = (int) lexico.getValue().stream().filter(texto::contains).count();
            if (senales > 0) {
                return new Animo(lexico.getKey(), senales);
            }
        }
        return new Animo("neutral", 0);
    }

    public static String dinero(Object valor) {
        return dinero(valor, "CLP");
    }

    /** "$1.040.000" o "UF 1.234,50". */
    public static String dinero(Object valor, String moneda) {
        double n = numero(valor);
        if ("UF".equals(moneda)) {
            return "UF " + formato("#,##0.00").format(new BigDecimal(n).setScale(2, RoundingMode.HALF_EVEN));
        }
        return "$" + formato("#,##0").format(new BigDecimal(n).setScale(0, RoundingMode.HALF_EVEN));
    }

    /** Lo vigente por moneda, con la mora de hoy: "$1.040.000 y UF 115,50". */
    public static String totales(List<Map<String, Object>> deudas) {
        Map<String, Double> porMoneda = new LinkedHashMap<>();
        for (Map<String, Object> d : deudas) {
            if (vigente(d)) {
                porMoneda.merge(moneda(d), numero(totalHoy(d)), Double::sum);
            }
        }
        if (porMoneda.isEmpty()) {
            return dinero(0);
        }
        List<String> partes = new ArrayList<>();
        porMoneda.forEach((moneda, total) -> partes.add(dinero(total, moneda)));
        return String.join(" y ", partes);
    }

    private static String linea(Map<String, Object> d) {
        String linea = "- " + d.get("acreedor") + " (" + d.get("concepto") + "): "
                + dinero(totalHoy(d), Objects.toString(d.get("moneda"), null)) + ", " + estado(d);
        List<String> ademas = new ArrayList<>();
        if (numero(d.get("interesMora")) > 0) {
            ademas.add("Incluye " + dinero(d.get("interesMora"), moneda(d)) + " de intereses por mora (" + tasa(d) + ").");
        }
        if (!oferta(d).isEmpty()) {
            ademas.add(oferta(d));
        }
        return ademas.isEmpty() ? linea : linea + ". " + String.join(" ", ademas);
    }

    /** "Patrimonio Inmuebles (Arriendo mensual), 1,5% mensual; ..." */
    private static String conSuTasa(List<Map<String, Object>> deudas) {
        return String.join("; ", deudas.stream()
                .map(d -> d.get("acreedor") + " (" + d.get("concepto") + "), " + tasa(d)).toList());
    }

    /** "1,5% mensual", o "1,5% mensual y 2% mensual" si son distintas. */
    private static String tasas(List<Map<String, Object>> deudas) {
        return String.join(" y ", deudas.stream().map(MotorLocal::tasa).distinct().toList());
    }

    /** Las cuotas, segun la tasa que pacto cada acreedor. */
    private static String lasCuotas(List<Map<String, Object>> vigentes) {
        List<Map<String, Object>> conTasa = vigentes.stream().filter(MotorLocal::conTasa).toList();
        String inicio = "Eliges entre 3 y 24 meses y ves la cuota antes de aceptar. ";
        String fin = " En UF, cada cuota se paga al valor de la UF del día en que pagas.";
        if (conTasa.isEmpty()) {
            return inicio + "No hay interés: el total es lo que debes hoy, y la última cuota absorbe el redondeo." + fin;
        }
        if (conTasa.size() == vigentes.size()) {
            return inicio + "Se repacta lo que debes hoy, con la mora, y las cuotas llevan el interés que pactaste ("
                    + tasas(conTasa) + "): en Ver planes ves cuánto pagas en total. La última cuota absorbe el redondeo."
                    + fin;
        }
        return inicio + "Las deudas con interés pactado (" + conSuTasa(conTasa) + ") lo llevan también en las "
                + "cuotas; en las demás no hay interés y el total es lo que debes hoy. La última cuota absorbe el "
                + "redondeo." + fin;
    }

    /** Para quien no puede pagar: las cuotas, con o sin interés. */
    private static String sinPlata(List<Map<String, Object>> vigentes, List<Map<String, Object>> deudas) {
        List<Map<String, Object>> conTasa = vigentes.stream().filter(MotorLocal::conTasa).toList();
        String saldo = " Tu saldo vigente es " + totales(deudas) + ".";
        if (conTasa.isEmpty()) {
            return "Puedes dividir lo que debes en 3 a 24 cuotas, sin interés: el total es "
                    + "lo mismo que debes hoy. En cada deuda está el botón Ver planes para simularlo sin "
                    + "comprometerte." + saldo;
        }
        String interes = conTasa.size() < vigentes.size()
                ? "Las deudas con interés pactado (" + conSuTasa(conTasa) + ") lo llevan también en las cuotas"
                : (vigentes.size() == 1 ? "Tu deuda lleva" : "Tus deudas llevan") + " el interés que pactaste ("
                        + tasas(conTasa) + "), también en las cuotas";
        return "Puedes dividir lo que debes en 3 a 24 cuotas. " + interes + ": en cada deuda está el botón Ver "
                + "planes para ver la cuota y el total sin comprometerte." + saldo;
    }

    /** Que intereses corren y que descuento hay. */
    private static String losIntereses(List<Map<String, Object>> vigentes) {
        if (vigentes.isEmpty()) {
            return "No tienes deudas por pagar, así que no corre ningún interés.";
        }
        List<Map<String, Object>> conTasa = vigentes.stream().filter(MotorLocal::conTasa).toList();
        if (conTasa.isEmpty()) {
            return (vigentes.size() == 1 ? "Tu deuda no tiene" : "Tus deudas no tienen") + " interés pactado: no "
                    + "se cobra nada extra, ni por atraso ni en cuotas.";
        }
        List<String> lineas = new ArrayList<>();
        lineas.add("Depende de lo que pactaste con la empresa. Una deuda con tasa crece por cada día de atraso, "
                + "y si la repactas, las cuotas llevan ese interés:");
        for (Map<String, Object> d : conTasa) {
            lineas.add("- " + d.get("acreedor") + " (" + d.get("concepto") + "): " + tasa(d)
                    + (numero(d.get("interesMora")) > 0
                    ? ", con " + dinero(d.get("interesMora"), moneda(d)) + " de intereses por mora hoy."
                    : ", sin mora por ahora."));
        }
        if (conTasa.size() < vigentes.size()) {
            lineas.add("Las demás no tienen interés.");
        }
        List<Map<String, Object>> conOferta = vigentes.stream().filter(d -> !oferta(d).isEmpty()).toList();
        if (conOferta.isEmpty()) {
            lineas.add("Por ahora no hay descuento por pronto pago. Si la empresa abre una campaña con descuento, "
                    + "lo vas a ver en Mis deudas.");
        } else {
            lineas.add("El descuento rebaja los intereses por mora, nunca el capital, y vale si pagas toda la "
                    + "deuda de una vez:");
            conOferta.forEach(d -> lineas.add("- " + d.get("acreedor") + " (" + d.get("concepto") + "): "
                    + minuscula(oferta(d))));
        }
        return String.join("\n", lineas);
    }

    /** Los descuentos por pronto pago, para sumarlos a una respuesta. */
    private static String ofertas(List<Map<String, Object>> vigentes, List<Map<String, Object>> conOferta) {
        if (vigentes.size() == 1) {
            return " " + oferta(conOferta.getFirst());
        }
        return conOferta.stream()
                .map(d -> " En " + d.get("acreedor") + " (" + d.get("concepto") + "): " + minuscula(oferta(d)))
                .reduce("", String::concat);
    }

    private static String minuscula(String frase) {
        return Character.toLowerCase(frase.charAt(0)) + frase.substring(1);
    }

    /** Lo que responde el asistente sin LLM. */
    public static String responder(String mensaje, List<Map<String, Object>> deudas) {
        String texto = normalizar(mensaje);
        String animo = sentimiento(mensaje).etiqueta();
        List<Map<String, Object>> vigentes = deudas.stream().filter(d -> vigente(d)).toList();
        List<Map<String, Object>> pagadas = deudas.stream().filter(d -> "paid".equals(d.get("estado"))).toList();
        Set<String> acreedores = new TreeSet<>();
        deudas.stream().map(d -> d.get("acreedor")).filter(Valor::presente)
                .map(String::valueOf).forEach(acreedores::add);

        if (animo.equals("desconfianza")) {
            String quien = acreedores.isEmpty() ? "" : " Tu deuda es con " + String.join(", ", acreedores) + ".";
            return "Es bueno que lo preguntes. Technical Bridge nunca te pide contraseñas ni datos "
                    + "bancarios, y nunca te manda un enlace para entrar: entras escribiendo la dirección "
                    + "tú mismo, con tu RUT y tu código." + quien + " Si algo no te cuadra, puedes confirmarlo "
                    + "directamente con esa empresa antes de pagar. Si la deuda no te corresponde, díselo "
                    + "a ellos: no tienes que pagar algo que no reconoces.";
        }

        String prefacio = "";
        if (animo.equals("frustracion")) {
            prefacio = "Entiendo que es una situación difícil. ";
            if (SIN_PLATA.matcher(texto).find()) {
                return prefacio + sinPlata(vigentes, deudas);
            }
        }

        if (SALUDO.matcher(texto).find()) {
            return "Hola, soy el asistente de Technical Bridge. Te puedo decir cuánto debes y a quién, "
                    + "cómo pagar en cuotas y cómo pagar. ¿Qué necesitas?";
        }
        if (texto.contains("certificad")) {
            if (!pagadas.isEmpty()) {
                return "Tienes " + pagadas.size() + " deuda(s) pagada(s). En Mis pagos, junto a cada "
                        + "una, está el botón Certificado para descargar el PDF.";
            }
            return "El certificado se emite cuando una deuda queda pagada por completo.";
        }
        if (INTERES.matcher(texto).find()) {
            return prefacio + losIntereses(vigentes);
        }
        if (CUOTAS.matcher(texto).find()) {
            return prefacio + lasCuotas(vigentes);
        }
        if (PAGAR.matcher(texto).find()) {
            List<Map<String, Object>> conOferta = vigentes.stream().filter(d -> !oferta(d).isEmpty()).toList();
            return prefacio + "En cada deuda está el botón Pagar: eliges la próxima cuota o todo el "
                    + "saldo, y pagas con Webpay, Mercado Pago o Khipu. Tu saldo se actualiza solo "
                    + "unos segundos después, y la empresa queda avisada."
                    + (conOferta.isEmpty() ? "" : ofertas(vigentes, conOferta));
        }
        if (SALDO.matcher(texto).find()) {
            if (deudas.isEmpty()) {
                return "No encuentro pagos pendientes a tu nombre.";
            }
            List<String> lineas = new ArrayList<>();
            lineas.add(prefacio + "Tienes " + vigentes.size() + " deuda(s) vigente(s), por " + totales(deudas)
                    + " en total.");
            vigentes.stream().limit(5).map(MotorLocal::linea).forEach(lineas::add);
            if (!pagadas.isEmpty()) {
                lineas.add("Además, " + pagadas.size() + " ya está(n) pagada(s).");
            }
            return String.join("\n", lineas);
        }
        if (animo.equals("positivo")) {
            return "Con gusto. Si necesitas algo más sobre tus pagos, pregúntame.";
        }
        if (!vigentes.isEmpty()) {
            return prefacio + "Tu saldo vigente es " + totales(deudas) + " en " + vigentes.size() + " deuda(s). "
                    + "Te puedo contar el detalle, cómo pagar en cuotas o cómo pagar.";
        }
        if (!deudas.isEmpty()) {
            return "No tienes saldo pendiente. Si pagaste todo, puedes descargar el certificado de cada deuda.";
        }
        return "Pregúntame cuánto debes, cómo pagar en cuotas o cómo pagar.";
    }

    /** Pendiente o en convenio: lo que todavia se debe. */
    static boolean vigente(Map<String, Object> d) {
        return d.get("estado") instanceof String estado && VIGENTES.contains(estado);
    }

    /** Lo que se paga hoy por la deuda: el saldo mas la mora. Si ms-debt no lo manda, el saldo. */
    static Object totalHoy(Map<String, Object> d) {
        return d.get("totalHoy") != null ? d.get("totalHoy") : d.get("saldo");
    }

    /** Si el acreedor pacto una tasa de interes. */
    static boolean conTasa(Map<String, Object> d) {
        return numero(d.get("tasaInteresMensual")) > 0;
    }

    /** "1,5% mensual". */
    static String tasa(Map<String, Object> d) {
        return formato("#,##0.##").format(new BigDecimal(numero(d.get("tasaInteresMensual")))
                .setScale(2, RoundingMode.HALF_EVEN)) + "% mensual";
    }

    /**
     * El descuento por pronto pago, como lo dice el portal: "Si pagas todo antes
     * del 3 de noviembre, te descontamos $9.400 de intereses." Vacio si no hay.
     */
    static String oferta(Map<String, Object> d) {
        if (numero(d.get("descuentoDisponible")) <= 0) {
            return "";
        }
        String cuando = d.get("descuentoHasta") instanceof String hasta ? " antes del " + fecha(hasta) : " ahora";
        return "Si pagas todo" + cuando + ", te descontamos " + dinero(d.get("descuentoDisponible"), moneda(d))
                + " de intereses.";
    }

    /** 2026-11-03 -> "3 de noviembre". */
    private static String fecha(String iso) {
        try {
            return LocalDate.parse(iso).format(DIA);
        } catch (DateTimeParseException noEsFecha) {
            return iso;
        }
    }

    /** El estado en palabras: "pendiente", "en convenio de pago"... */
    static String estado(Map<String, Object> d) {
        Object estado = d.get("estado");
        return ESTADOS.getOrDefault(String.valueOf(estado), String.valueOf(estado));
    }

    /** La moneda de la deuda; sin moneda, pesos. */
    static String moneda(Map<String, Object> d) {
        return Valor.presente(d.get("moneda")) ? String.valueOf(d.get("moneda")) : "CLP";
    }

    /** Un monto como numero; lo que no es numero vale cero. */
    static double numero(Object valor) {
        double n;
        if (valor instanceof Number numero) {
            n = numero.doubleValue();
        } else if (valor instanceof Boolean si) {
            n = si ? 1 : 0;
        } else {
            try {
                n = Double.parseDouble(Objects.toString(valor, "").strip());
            } catch (NumberFormatException noEsNumero) {
                n = 0;
            }
        }
        return Double.isFinite(n) ? n : 0;
    }

    private static DecimalFormat formato(String patron) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        return new DecimalFormat(patron, simbolos);
    }
}
