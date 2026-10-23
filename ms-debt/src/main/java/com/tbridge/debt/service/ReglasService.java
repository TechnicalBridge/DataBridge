package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.ReglasResponse;
import com.tbridge.debt.dto.response.ReglasResponse.Grupo;
import com.tbridge.debt.dto.response.ReglasResponse.Regla;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;

/**
 * Las reglas de cobranza y de contacto que rigen en ms-debt, para la pantalla
 * Reglas del panel. Salen de la misma configuracion y de los mismos objetos
 * que las aplican: los feriados son los de {@link HorarioDeContacto}, no una
 * copia.
 */
@Service
public class ReglasService {

    private static final Locale ES_CL = Locale.forLanguageTag("es-CL");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES_CL);
    private static final String LEY = "Ley 19.496, art. 37";

    private final int minDiasMora;
    private final BigDecimal tasaMaxima;
    private final int diasAntes;
    private final HorarioDeContacto horario;
    private final LimiteDeContacto limite;
    private final boolean entregaPorApi;

    public ReglasService(@Value("${app.cartera.min-dias-mora:30}") int minDiasMora,
                         @Value("${app.intereses.tasa-maxima-mensual:3.0}") BigDecimal tasaMaxima,
                         @Value("${app.recordatorios.dias-antes:3}") int diasAntes,
                         HorarioDeContacto horario,
                         LimiteDeContacto limite,
                         @Value("${app.integracion.api:false}") boolean entregaPorApi) {
        this.minDiasMora = minDiasMora;
        this.tasaMaxima = tasaMaxima;
        this.diasAntes = diasAntes;
        this.horario = horario;
        this.limite = limite;
        this.entregaPorApi = entregaPorApi;
    }

    /** Lo que rige hoy: los feriados son los de este ano y los del siguiente. */
    public ReglasResponse reglas(LocalDate hoy) {
        Grupo cobranza = new Grupo("Cobranza", List.of(
                Regla.de("Cómo entran los deudores", entregaPorApi
                        ? "Por campaña, desde el portal, y también por la API con una clave"
                        : "Solo por campaña, cargados desde el portal", "INTEGRACION_API"),
                Regla.de("Una deuda entra a cobranza con", dias(minDiasMora) + " de mora, o más", "MIN_DIAS_MORA"),
                Regla.de("Tasa de interés máxima", tasaMaxima.stripTrailingZeros().toPlainString().replace('.', ',')
                        + " % mensual", "INTERES_TASA_MAXIMA_MENSUAL"),
                Regla.de("El recordatorio de una cuota llega", dias(diasAntes) + " antes de que venza",
                        "RECORDATORIO_DIAS_ANTES")));
        Grupo contacto = new Grupo("Contacto con el deudor", List.of(
                horario.apagado()
                        ? new Regla("Cuándo se le puede escribir",
                                "APAGADO PARA PROBAR: se le escribe a cualquier hora. La ley pide de lunes a sábado, "
                                        + "de 8:00 a 20:00, y nunca un feriado",
                                List.of(), "CONTACTO_SIN_HORARIO", LEY + ". Nunca en producción")
                        : Regla.fija("Cuándo se le puede escribir",
                                "De lunes a sábado, de 8:00 a 20:00, y nunca un feriado", LEY),
                limite.apagado()
                        ? new Regla("Cuántas veces",
                                "APAGADO PARA PROBAR: se le escribe cuantas veces se pida. La ley pide "
                                        + LimiteDeContacto.POR_SEMANA + " correos por semana, con al menos "
                                        + dias(LimiteDeContacto.DIAS_ENTRE) + " entre uno y otro",
                                List.of(), "CONTACTO_SIN_LIMITE", LEY + ". Nunca en producción")
                        : Regla.fija("Cuántas veces", LimiteDeContacto.POR_SEMANA + " correos por semana, con al menos "
                                + dias(LimiteDeContacto.DIAS_ENTRE) + " entre uno y otro", LEY),
                feriados(hoy.getYear()),
                feriados(hoy.getYear() + 1)));
        //  El modelo de negocio (#200): fijo, es el del equipo. Lo aplica ComisionService.
        java.text.NumberFormat pesos = java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("es-CL"));
        Grupo negocio = new Grupo("Modelo de negocio", ModeloDeNegocio.TRAMOS.stream()
                .map(t -> Regla.fija(t.hasta() == null
                                ? "Si se recupera más de $" + pesos.format(t.desde())
                                : "Si se recupera más de $" + pesos.format(t.desde()) + " y hasta $"
                                        + pesos.format(t.hasta()),
                        t.porcentaje().stripTrailingZeros().toPlainString() + " % de lo recuperado en el mes",
                        "P(G) = G · p(G), sobre todo lo recuperado. La paga quien cargó la deuda"))
                .toList());
        return new ReglasResponse("ms-debt", List.of(cobranza, contacto, negocio));
    }

    private Regla feriados(int ano) {
        SortedSet<LocalDate> dias = horario.feriadosDe(ano);
        List<String> lista = dias.stream()
                .map(dia -> DIA.format(dia) + (horario.esDecretado(dia) ? " (decretado)" : ""))
                .toList();
        return new Regla("Feriados de " + ano, dias.size() + " días sin correos", lista, "CONTACTO_FERIADOS",
                "Los nacionales se calculan con las reglas de la ley; CONTACTO_FERIADOS suma los que se decretan");
    }

    private static String dias(int cuantos) {
        return cuantos + (cuantos == 1 ? " día" : " días");
    }
}
