package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.util.Rut;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La planilla de la cartera en Excel (#201): una hoja <b>Deudores</b>, con una
 * fila por persona, y una hoja <b>Deudas</b>, con una fila por <b>mes</b> de
 * cada deuda, ligada al deudor por su RUT.
 *
 * <p>Como el CSV, se traduce a la Cartera v1 y entra por la misma ingesta, con
 * las mismas reglas. Lo que se revisa aca es la forma de la planilla: que cada
 * columna traiga su tipo (una fecha, un numero, un valor de la lista), que el
 * RUT de cada deuda este en <i>Deudores</i> y que una deuda diga lo mismo en
 * todos sus meses. Se juntan <b>todos</b> los problemas, cada uno con su hoja,
 * su fila y su columna, porque quien los arregla esta mirando la planilla. Lo
 * de negocio (el digito del RUT, la mora, la tasa) lo sigue decidiendo la
 * ingesta, deuda por deuda.
 */
public final class CarteraExcel {

    static final String INSTRUCCIONES = "Instrucciones";
    static final String DEUDORES = "Deudores";
    static final String DEUDAS = "Deudas";

    /** Una columna: el encabezado que se busca al leer, y lo que la plantilla explica de ella. */
    record Columna(String titulo, String tipo, String obligatoria, String ejemplo, String explicacion) {
    }

    static final Columna RUT = new Columna("RUT", "Texto", "Sí", "16.482.337-7",
            "Con o sin puntos, con guion y dígito verificador.");
    static final Columna TIPO = new Columna("Tipo", "Lista", "Sí", "persona", "persona o empresa.");
    static final Columna NOMBRE = new Columna("Nombre", "Texto", "Sí", "Felipe Rojas Muñoz",
            "El nombre completo, o la razón social.");
    static final Columna CORREO = new Columna("Correo", "Texto", "Sí, o el teléfono", "felipe.rojas@correo.cl",
            "Adonde le llega su código de acceso.");
    static final Columna TELEFONO = new Columna("Teléfono", "Texto", "No", "+56 9 4412 0387", "");

    static final Columna ID = new Columna("Id de la deuda", "Texto", "Sí", "CTR-2026-014",
            "El número con que tu sistema conoce la deuda: el contrato, la matrícula o la ficha. Se repite en cada mes.");
    static final Columna RUT_DEUDOR = new Columna("RUT del deudor", "Texto", "Sí, salvo al retirar", "16.482.337-7",
            "Tiene que estar en la hoja Deudores.");
    static final Columna ACCION = new Columna("Acción", "Lista", "Sí", "registrar",
            "registrar: la deuda, con una fila por cada mes impago. al día: el cliente no debe nada (una sola fila, "
                    + "sin mes). retirar: sacarla de la cobranza (una sola fila, con el motivo).");
    static final Columna MONEDA = new Columna("Moneda", "Lista", "Sí, salvo al retirar", "CLP",
            "CLP o UF. La misma en todos los meses de la deuda.");
    static final Columna CONCEPTO = new Columna("Concepto de la deuda", "Texto", "Sí, salvo al retirar",
            "Arriendo Depto. 302", "Qué es la deuda. El mismo en todos sus meses.");
    static final Columna MES = new Columna("Mes", "Fecha (mes y año)", "Sí, al registrar", "jul-2026",
            "El mes que se debe: una fila por mes.");
    static final Columna MONTO = new Columna("Monto", "Número", "Sí, al registrar", "350000",
            "Lo que se debe de ese mes. En CLP sin decimales; en UF, con hasta dos. Sin puntos de miles.");
    static final Columna VENCIMIENTO = new Columna("Vencimiento", "Fecha", "Sí, al registrar", "05-07-2026",
            "Cuándo venció el pago de ese mes.");
    static final Columna CONCEPTO_MES = new Columna("Concepto del mes", "Texto", "No", "Arriendo julio",
            "Si va vacío: el concepto de la deuda y el mes.");
    static final Columna TASA = new Columna("Tasa de interés mensual", "Número (%)", "No", "1,5",
            "Solo si la pactaste con el deudor. La misma en todos los meses.");
    static final Columna MOTIVO = new Columna("Motivo del retiro", "Lista", "Sí, al retirar", "pago_directo",
            "Por qué sale de la cobranza: pago_directo, acuerdo_directo, error, disputa_resuelta, "
                    + "fuera_de_mandato u otro.");
    static final Columna REFERENCIAS = new Columna("Referencias", "Texto", "No", "contrato=CTR-2026-014 | piso=3",
            "Lo que te sirva para reconocerla, como clave=valor separados por |.");

    static final List<Columna> COLUMNAS_DEUDORES = List.of(RUT, TIPO, NOMBRE, CORREO, TELEFONO);
    static final List<Columna> COLUMNAS_DEUDAS = List.of(ID, RUT_DEUDOR, ACCION, MONEDA, CONCEPTO, MES, MONTO,
            VENCIMIENTO, CONCEPTO_MES, TASA, MOTIVO, REFERENCIAS);

    static final List<String> TIPOS = List.of("persona", "empresa");
    static final List<String> ACCIONES = List.of("registrar", "al día", "retirar");
    static final List<String> MONEDAS = List.of("CLP", "UF");
    static final List<String> MOTIVOS = List.of("pago_directo", "acuerdo_directo", "error", "disputa_resuelta",
            "fuera_de_mandato", "otro");

    /** Mas que esto no se muestra: con tantos, el problema es la planilla entera. */
    static final int MAX_PROBLEMAS = 50;

    private static final JsonNodeFactory NODOS = JsonNodeFactory.instance;
    private static final Locale CHILE = Locale.forLanguageTag("es-CL");
    private static final Map<String, Integer> MESES = new HashMap<>();

    static {
        for (int m = 1; m <= 12; m++) {
            String nombre = simple(java.time.Month.of(m).getDisplayName(TextStyle.FULL, CHILE));
            MESES.put(nombre, m);
            MESES.put(nombre.substring(0, 3), m);
        }
        MESES.put("set", 9);
        MESES.put("sept", 9);
    }

    private CarteraExcel() {
    }

    /** Si los bytes son una planilla de Excel (.xlsx, o el .xls de antes) y no un CSV. */
    public static boolean esExcel(byte[] archivo) {
        //  .xlsx es un zip (PK); el .xls de antes, un documento OLE2.
        return archivo.length >= 4
                && ((archivo[0] == 'P' && archivo[1] == 'K')
                || ((archivo[0] & 0xFF) == 0xD0 && (archivo[1] & 0xFF) == 0xCF));
    }

    public static ObjectNode leer(byte[] archivo, CarteraCsv.Lote lote) {
        try (Workbook libro = WorkbookFactory.create(new ByteArrayInputStream(archivo))) {
            return new Lector(libro).leer(lote);
        } catch (CarteraInvalida e) {
            throw e;
        } catch (EncryptedDocumentException e) {
            throw invalido("La planilla tiene clave: guárdala sin clave y vuelve a subirla");
        } catch (IOException | RuntimeException e) {
            throw invalido("No se pudo leer el archivo como una planilla de Excel. Usa la plantilla y guárdala como .xlsx");
        }
    }

    /** Una fila de Deudores, ya leida. */
    private record Deudor(int fila, String rut, String tipo, String nombre, String correo, String telefono) {
    }

    /** Lo que una deuda tiene que repetir igual en todos sus meses. */
    private record DeLaDeuda(String rut, String accion, String moneda, String concepto, BigDecimal tasa,
                             String referencias) {
    }

    private static final class Lector {

        private final Workbook libro;
        private final DataFormatter formato = new DataFormatter(CHILE);
        private final FormulaEvaluator formulas;
        private final List<String> problemas = new ArrayList<>();

        Lector(Workbook libro) {
            this.libro = libro;
            this.formulas = libro.getCreationHelper().createFormulaEvaluator();
        }

        ObjectNode leer(CarteraCsv.Lote lote) {
            Sheet hojaDeudores = hoja(DEUDORES);
            Sheet hojaDeudas = hoja(DEUDAS);
            if (hojaDeudores == null || hojaDeudas == null) {
                throw invalido("A la planilla le falta la hoja " + (hojaDeudores == null ? DEUDORES : DEUDAS)
                        + ". Descarga la plantilla y llena sus hojas Deudores y Deudas");
            }
            Map<String, Integer> enDeudores = encabezado(hojaDeudores, COLUMNAS_DEUDORES);
            Map<String, Integer> enDeudas = encabezado(hojaDeudas, COLUMNAS_DEUDAS);
            if (!problemas.isEmpty()) {
                throw conProblemas();
            }
            if (hojaDeudas.getLastRowNum() > CarteraCsv.MAX_FILAS) {
                throw invalido("La hoja Deudas tiene mas de " + CarteraCsv.MAX_FILAS
                        + " filas: divídela en varias planillas");
            }
            Map<String, Deudor> deudores = deudores(hojaDeudores, enDeudores);
            Map<String, ObjectNode> deudas = deudas(hojaDeudas, enDeudas, deudores);
            if (problemas.isEmpty() && deudas.isEmpty()) {
                problemas.add("La hoja Deudas está vacía: no hay nada que cargar");
            }
            if (!problemas.isEmpty()) {
                throw conProblemas();
            }
            return CarteraCsv.envolver(deudas.values(), lote);
        }

        // ------------------------------------------------------------------
        //  Deudores: una fila por persona
        // ------------------------------------------------------------------

        private Map<String, Deudor> deudores(Sheet hoja, Map<String, Integer> col) {
            Map<String, Deudor> porRut = new LinkedHashMap<>();
            for (int n = 1; n <= hoja.getLastRowNum(); n++) {
                Row fila = hoja.getRow(n);
                if (vacia(fila, col.values())) {
                    continue;
                }
                int numero = n + 1;
                String rut = rut(texto(fila, col, RUT));
                String tipo = texto(fila, col, TIPO).toLowerCase(Locale.ROOT);
                String nombre = texto(fila, col, NOMBRE);
                String correo = texto(fila, col, CORREO);
                String telefono = texto(fila, col, TELEFONO);
                if (rut.isEmpty()) {
                    problema(DEUDORES, numero, RUT, "falta el RUT");
                    continue;
                }
                Deudor antes = porRut.get(rut);
                if (antes != null) {
                    problema(DEUDORES, numero, RUT, "el RUT " + rut + " ya está en la fila " + antes.fila()
                            + ". Cada persona va una sola vez; sus deudas, en la hoja Deudas");
                    continue;
                }
                if (!TIPOS.contains(tipo)) {
                    problema(DEUDORES, numero, TIPO, tipo.isEmpty() ? "falta el tipo: persona o empresa"
                            : "'" + tipo + "' no es un tipo: persona o empresa");
                }
                if (nombre.isEmpty()) {
                    problema(DEUDORES, numero, NOMBRE, "falta el nombre");
                }
                if (correo.isEmpty() && telefono.isEmpty()) {
                    problema(DEUDORES, numero, CORREO, "falta el correo o el teléfono: sin ellos no le llega su código");
                }
                porRut.put(rut, new Deudor(numero, rut, tipo, nombre, correo, telefono));
            }
            return porRut;
        }

        // ------------------------------------------------------------------
        //  Deudas: una fila por mes
        // ------------------------------------------------------------------

        private Map<String, ObjectNode> deudas(Sheet hoja, Map<String, Integer> col, Map<String, Deudor> deudores) {
            Map<String, ObjectNode> deudas = new LinkedHashMap<>();
            Map<String, Integer> primeraFila = new HashMap<>();
            Map<String, DeLaDeuda> comoEs = new HashMap<>();
            Map<String, Integer> filaDelMes = new HashMap<>();

            for (int n = 1; n <= hoja.getLastRowNum(); n++) {
                Row fila = hoja.getRow(n);
                if (vacia(fila, col.values())) {
                    continue;
                }
                int numero = n + 1;
                int antes = problemas.size();
                String id = texto(fila, col, ID);
                if (id.isEmpty()) {
                    problema(DEUDAS, numero, ID, "falta el id de la deuda");
                    continue;
                }
                String accion = accion(texto(fila, col, ACCION));
                if (accion == null) {
                    problema(DEUDAS, numero, ACCION, "'" + texto(fila, col, ACCION)
                            + "' no es una acción: registrar, al día o retirar");
                    continue;
                }

                if (accion.equals("retirar")) {
                    if (deudas.containsKey(id)) {
                        problema(DEUDAS, numero, ID, "la deuda " + id + " ya está en la fila " + primeraFila.get(id)
                                + ". Un retiro va en una sola fila");
                        continue;
                    }
                    String motivo = texto(fila, col, MOTIVO).toLowerCase(Locale.ROOT);
                    if (!MOTIVOS.contains(motivo)) {
                        problema(DEUDAS, numero, MOTIVO, motivo.isEmpty() ? "falta el motivo del retiro"
                                : "'" + motivo + "' no es un motivo de retiro");
                        continue;
                    }
                    ObjectNode retiro = NODOS.objectNode();
                    retiro.put("id_externo", id);
                    retiro.put("accion", "retirar");
                    retiro.put("motivo_retiro", motivo);
                    deudas.put(id, retiro);
                    primeraFila.put(id, numero);
                    comoEs.put(id, new DeLaDeuda(null, accion, null, null, null, null));
                    continue;
                }

                //  registrar o al dia: la deuda de una persona de Deudores.
                String rut = rut(texto(fila, col, RUT_DEUDOR));
                Deudor deudor = deudores.get(rut);
                if (rut.isEmpty()) {
                    problema(DEUDAS, numero, RUT_DEUDOR, "falta el RUT del deudor");
                } else if (deudor == null) {
                    problema(DEUDAS, numero, RUT_DEUDOR, "el RUT " + rut + " no está en la hoja Deudores");
                }
                String moneda = texto(fila, col, MONEDA).toUpperCase(Locale.ROOT);
                if (!MONEDAS.contains(moneda)) {
                    problema(DEUDAS, numero, MONEDA, moneda.isEmpty() ? "falta la moneda: CLP o UF"
                            : "'" + moneda + "' no es una moneda: CLP o UF");
                }
                String concepto = texto(fila, col, CONCEPTO);
                if (concepto.isEmpty()) {
                    problema(DEUDAS, numero, CONCEPTO, "falta el concepto de la deuda");
                }
                BigDecimal tasa = vacio(fila, col, TASA) ? null : numero(fila, col, TASA, DEUDAS, numero);
                DeLaDeuda esta = new DeLaDeuda(rut, accion, moneda, concepto, tasa, texto(fila, col, REFERENCIAS));

                DeLaDeuda primera = comoEs.get(id);
                if (primera != null) {
                    if (!primera.accion().equals(accion) || !accion.equals("registrar")) {
                        problema(DEUDAS, numero, ID, "la deuda " + id + " ya está en la fila " + primeraFila.get(id)
                                + (accion.equals("al día") || primera.accion().equals("al día")
                                ? ". Una deuda al día va en una sola fila, sin meses"
                                : ". Una deuda que se retira va en una sola fila"));
                        continue;
                    }
                    String distinta = distinta(primera, esta);
                    if (distinta != null) {
                        problema(DEUDAS, numero, columnaDe(distinta), "no dice lo mismo que en la fila "
                                + primeraFila.get(id) + ": la deuda " + id + " lleva igual " + distinta
                                + " en todos sus meses");
                        continue;
                    }
                }

                if (accion.equals("al día")) {
                    for (Columna delMes : List.of(MES, MONTO, VENCIMIENTO)) {
                        if (!vacio(fila, col, delMes)) {
                            problema(DEUDAS, numero, delMes, "una deuda al día no lleva " + delMes.titulo()
                                    + ": el cliente no debe nada");
                        }
                    }
                }
                ObjectNode cargo = accion.equals("registrar") ? cargo(fila, col, numero, id, moneda, concepto,
                        filaDelMes) : null;
                if (problemas.size() > antes || deudor == null) {
                    continue;
                }

                ObjectNode deuda = deudas.get(id);
                if (deuda == null) {
                    deuda = nuevaDeuda(id, deudor, esta);
                    deudas.put(id, deuda);
                    primeraFila.put(id, numero);
                    comoEs.put(id, esta);
                }
                if (cargo != null) {
                    ((com.fasterxml.jackson.databind.node.ArrayNode) deuda.get("cargos")).add(cargo);
                }
            }
            return deudas;
        }

        private ObjectNode cargo(Row fila, Map<String, Integer> col, int numero, String id, String moneda,
                                 String concepto, Map<String, Integer> filaDelMes) {
            YearMonth mes = mes(fila, col, numero);
            BigDecimal monto = vacio(fila, col, MONTO) ? null : numero(fila, col, MONTO, DEUDAS, numero);
            LocalDate vence = fecha(fila, col, VENCIMIENTO, numero);
            if (mes == null && vacio(fila, col, MES)) {
                problema(DEUDAS, numero, MES, "falta el mes: una fila por cada mes que se debe");
            }
            if (monto == null && vacio(fila, col, MONTO)) {
                problema(DEUDAS, numero, MONTO, "falta el monto");
            }
            if (vence == null && vacio(fila, col, VENCIMIENTO)) {
                problema(DEUDAS, numero, VENCIMIENTO, "falta el vencimiento");
            }
            if (monto != null) {
                if (monto.signum() <= 0) {
                    problema(DEUDAS, numero, MONTO, "el monto tiene que ser mayor que cero");
                } else if ("CLP".equals(moneda) && monto.stripTrailingZeros().scale() > 0) {
                    problema(DEUDAS, numero, MONTO, "un monto en CLP va sin decimales: " + monto.toPlainString());
                } else if ("UF".equals(moneda) && monto.stripTrailingZeros().scale() > 2) {
                    problema(DEUDAS, numero, MONTO, "un monto en UF lleva hasta dos decimales: " + monto.toPlainString());
                }
            }
            if (mes != null) {
                Integer otra = filaDelMes.putIfAbsent(id + "|" + mes, numero);
                if (otra != null) {
                    problema(DEUDAS, numero, MES, "el mes " + mes + " de la deuda " + id
                            + " ya está en la fila " + otra);
                }
            }
            if (mes == null || monto == null || vence == null) {
                return null;
            }
            String delMes = texto(fila, col, CONCEPTO_MES);
            ObjectNode cargo = NODOS.objectNode();
            cargo.put("concepto", delMes.isEmpty() ? concepto + " · " + enPalabras(mes) : delMes);
            cargo.put("periodo", mes.toString());
            BigDecimal valor = monto.stripTrailingZeros();
            if (valor.scale() <= 0) {
                cargo.put("monto", valor.longValueExact());
            } else {
                cargo.put("monto", valor);
            }
            cargo.put("fecha_vencimiento", vence.toString());
            return cargo;
        }

        private static ObjectNode nuevaDeuda(String id, Deudor deudor, DeLaDeuda esta) {
            ObjectNode deuda = NODOS.objectNode();
            deuda.put("id_externo", id);
            ObjectNode persona = deuda.putObject("deudor");
            persona.put("rut", deudor.rut());
            persona.put("tipo", deudor.tipo());
            persona.put("nombre", deudor.nombre());
            if (!deudor.correo().isEmpty()) {
                persona.put("correo", deudor.correo());
            }
            if (!deudor.telefono().isEmpty()) {
                persona.put("telefono", deudor.telefono());
            }
            deuda.put("moneda", esta.moneda());
            deuda.put("concepto", esta.concepto());
            if (!esta.referencias().isEmpty()) {
                ObjectNode refs = deuda.putObject("referencias");
                for (String par : esta.referencias().split("\\|")) {
                    int igual = par.indexOf('=');
                    if (igual > 0) {
                        refs.put(par.substring(0, igual).trim(), par.substring(igual + 1).trim());
                    }
                }
            }
            if (esta.tasa() != null) {
                deuda.put("tasa_interes_mensual", esta.tasa());
            }
            deuda.putArray("cargos");
            return deuda;
        }

        /** La primera columna de la deuda que no dice lo mismo que en su primera fila. */
        private static String distinta(DeLaDeuda primera, DeLaDeuda esta) {
            if (!primera.rut().equals(esta.rut())) return RUT_DEUDOR.titulo();
            if (!primera.moneda().equals(esta.moneda())) return MONEDA.titulo();
            if (!primera.concepto().equals(esta.concepto())) return CONCEPTO.titulo();
            if (!Objects.equals(primera.tasa() == null ? null : primera.tasa().stripTrailingZeros(),
                    esta.tasa() == null ? null : esta.tasa().stripTrailingZeros())) return TASA.titulo();
            return null;
        }

        private static Columna columnaDe(String titulo) {
            return COLUMNAS_DEUDAS.stream().filter(c -> c.titulo().equals(titulo)).findFirst().orElse(ID);
        }

        private static String accion(String crudo) {
            String simple = simple(crudo);
            if (simple.isEmpty() || simple.equals("registrar")) return "registrar";
            if (simple.equals("al dia") || simple.equals("al_dia")) return "al día";
            if (simple.equals("retirar")) return "retirar";
            return null;
        }

        // ------------------------------------------------------------------
        //  Leer las celdas, con su tipo
        // ------------------------------------------------------------------

        private Sheet hoja(String nombre) {
            for (int i = 0; i < libro.getNumberOfSheets(); i++) {
                if (simple(libro.getSheetName(i)).equals(simple(nombre))) {
                    return libro.getSheetAt(i);
                }
            }
            return null;
        }

        /** Donde esta cada columna, por su encabezado. Las que faltan, como problema. */
        private Map<String, Integer> encabezado(Sheet hoja, List<Columna> columnas) {
            Map<String, Integer> donde = new HashMap<>();
            Row primera = hoja.getRow(0);
            if (primera != null) {
                for (Cell celda : primera) {
                    donde.put(simple(formato.formatCellValue(celda)), celda.getColumnIndex());
                }
            }
            Map<String, Integer> col = new LinkedHashMap<>();
            List<String> faltan = new ArrayList<>();
            for (Columna c : columnas) {
                Integer i = donde.get(simple(c.titulo()));
                if (i == null) {
                    faltan.add(c.titulo());
                } else {
                    col.put(c.titulo(), i);
                }
            }
            if (!faltan.isEmpty()) {
                problemas.add("Hoja " + hoja.getSheetName() + ", fila 1: faltan las columnas "
                        + String.join(", ", faltan) + ". Usa la plantilla sin cambiarle los encabezados");
            }
            return col;
        }

        private Cell celda(Row fila, Map<String, Integer> col, Columna columna) {
            Integer i = col.get(columna.titulo());
            return fila == null || i == null ? null : fila.getCell(i);
        }

        private String texto(Row fila, Map<String, Integer> col, Columna columna) {
            Cell c = celda(fila, col, columna);
            return c == null ? "" : formato.formatCellValue(c, formulas).trim();
        }

        private boolean vacio(Row fila, Map<String, Integer> col, Columna columna) {
            return texto(fila, col, columna).isEmpty();
        }

        private boolean vacia(Row fila, java.util.Collection<Integer> columnas) {
            if (fila == null) {
                return true;
            }
            for (int i : columnas) {
                Cell c = fila.getCell(i);
                if (c != null && !formato.formatCellValue(c, formulas).isBlank()) {
                    return false;
                }
            }
            return true;
        }

        /** Un numero: de una celda numerica tal cual; de un texto, con coma decimal y sin puntos de miles. */
        private BigDecimal numero(Row fila, Map<String, Integer> col, Columna columna, String hoja, int numero) {
            Cell c = celda(fila, col, columna);
            if (c == null) {
                return null;
            }
            CellType tipo = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
            if (tipo == CellType.NUMERIC) {
                return plano(new BigDecimal(String.valueOf(c.getNumericCellValue())));
            }
            String crudo = texto(fila, col, columna).replace(" ", "").replace("$", "").replace("%", "");
            if (crudo.matches("\\d{1,3}(\\.\\d{3})+")) {
                crudo = crudo.replace(".", "");             // 185.000: puntos de miles
            }
            if (crudo.contains(".") && crudo.contains(",")) {
                problema(hoja, numero, columna, "'" + texto(fila, col, columna)
                        + "' no es un número: va sin puntos de miles, como 185000 o 38,5");
                return null;
            }
            try {
                return plano(new BigDecimal(crudo.replace(',', '.')));
            } catch (NumberFormatException e) {
                problema(hoja, numero, columna, "'" + texto(fila, col, columna) + "' no es un número");
                return null;
            }
        }

        private static final List<DateTimeFormatter> FECHAS = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d-M-uuuu"),
                DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d.M.uuuu"));

        private LocalDate fecha(Row fila, Map<String, Integer> col, Columna columna, int numero) {
            Cell c = celda(fila, col, columna);
            if (c == null || vacio(fila, col, columna)) {
                return null;
            }
            LocalDate dia = deExcel(c);
            if (dia == null) {
                String crudo = texto(fila, col, columna);
                for (DateTimeFormatter forma : FECHAS) {
                    try {
                        dia = LocalDate.parse(crudo, forma);
                        break;
                    } catch (DateTimeParseException e) {
                        //  la siguiente
                    }
                }
            }
            if (dia == null || dia.getYear() < 2000 || dia.getYear() > 2100) {
                problema(DEUDAS, numero, columna, "'" + texto(fila, col, columna)
                        + "' no es una fecha. Escríbela como fecha: 05-07-2026");
                return null;
            }
            return dia;
        }

        private static final Pattern MES_TEXTO = Pattern.compile("([a-z]+)[\\s\\-/.]*(?:de\\s+)?(\\d{4})");

        private YearMonth mes(Row fila, Map<String, Integer> col, int numero) {
            Cell c = celda(fila, col, MES);
            if (c == null || vacio(fila, col, MES)) {
                return null;
            }
            LocalDate dia = deExcel(c);
            if (dia != null && dia.getYear() >= 2000 && dia.getYear() <= 2100) {
                return YearMonth.from(dia);
            }
            String crudo = simple(texto(fila, col, MES));
            try {
                if (crudo.matches("\\d{4}-\\d{1,2}")) {
                    String[] p = crudo.split("-");
                    return YearMonth.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                }
                if (crudo.matches("\\d{1,2}[-/]\\d{4}")) {
                    String[] p = crudo.split("[-/]");
                    return YearMonth.of(Integer.parseInt(p[1]), Integer.parseInt(p[0]));
                }
                Matcher m = MES_TEXTO.matcher(crudo);
                if (m.matches() && MESES.containsKey(m.group(1))) {
                    return YearMonth.of(Integer.parseInt(m.group(2)), MESES.get(m.group(1)));
                }
            } catch (java.time.DateTimeException | NumberFormatException e) {
                //  cae al problema de abajo
            }
            problema(DEUDAS, numero, MES, "'" + texto(fila, col, MES)
                    + "' no es un mes. Escríbelo como fecha (jul-2026) o como 2026-07");
            return null;
        }

        /** Una celda que Excel guarda como fecha (un numero de dias), o nada. */
        private static LocalDate deExcel(Cell c) {
            CellType tipo = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
            if (tipo != CellType.NUMERIC || !DateUtil.isValidExcelDate(c.getNumericCellValue())) {
                return null;
            }
            return DateUtil.getLocalDateTime(c.getNumericCellValue()).toLocalDate();
        }

        /** Sin ceros de mas, pero nunca en notacion cientifica: 350000 y no 3.5E+5. */
        private static BigDecimal plano(BigDecimal valor) {
            BigDecimal sin = valor.stripTrailingZeros();
            return sin.scale() < 0 ? sin.setScale(0) : sin;
        }

        private static String rut(String crudo) {
            return crudo.isEmpty() ? "" : Rut.normalizar(crudo);
        }

        private void problema(String hoja, int fila, Columna columna, String que) {
            problemas.add("Hoja " + hoja + ", fila " + fila + ", columna " + columna.titulo() + ": " + que);
        }

        private CarteraInvalida conProblemas() {
            int total = problemas.size();
            List<String> detalles = new ArrayList<>(problemas.subList(0, Math.min(total, MAX_PROBLEMAS)));
            if (total > MAX_PROBLEMAS) {
                detalles.add("… y " + (total - MAX_PROBLEMAS) + " más");
            }
            String mensaje = total == 1 ? "La planilla tiene un problema: " + problemas.get(0)
                    : "La planilla tiene " + total + " problemas. Corrígelos y vuelve a revisarla";
            return new CarteraInvalida("excel_invalido", mensaje, 400, total == 1 ? List.of() : detalles);
        }
    }

    /** julio de 2026 */
    static String enPalabras(YearMonth mes) {
        return mes.getMonth().getDisplayName(TextStyle.FULL, CHILE) + " de " + mes.getYear();
    }

    /** Para comparar encabezados y valores: sin tildes, en minusculas y sin espacios de mas. */
    static String simple(String texto) {
        String sinTildes = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static CarteraInvalida invalido(String mensaje) {
        return new CarteraInvalida("excel_invalido", mensaje, 400);
    }
}
