package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.DebtSummaryResponse;
import com.tbridge.debt.dto.response.DeudaAdminResponse;
import com.tbridge.debt.dto.response.DeudorEncontradoResponse;
import com.tbridge.debt.dto.response.FichaDeudorResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.AdminLog;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.AdminLogRepository;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.DeudasDelDeudor;
import com.tbridge.debt.util.Rut;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Buscar a un deudor desde el panel, en todas las empresas, y abrir su ficha
 * (#434). El panel no muestra deudores en ninguna otra parte: la ficha se abre
 * a proposito, para atender a alguien que reclama o pide ayuda, y cada vez
 * queda en la bitacora del panel con el RUT del deudor y quien la abrio.
 */
@Service
public class DeudoresAdminService {

    /** Lo minimo que se escribe para buscar: con menos, la lista seria casi todos. */
    static final int MINIMO_PARA_BUSCAR = 3;
    private static final int MAXIMO_POR_PAGINA = 50;

    private final DebtorRepository deudores;
    private final DebtRepository deudas;
    private final ContactRepository contactos;
    private final AdminLogRepository bitacora;
    private final DebtService resumenes;

    public DeudoresAdminService(DebtorRepository deudores, DebtRepository deudas, ContactRepository contactos,
                                AdminLogRepository bitacora, DebtService resumenes) {
        this.deudores = deudores;
        this.deudas = deudas;
        this.contactos = contactos;
        this.bitacora = bitacora;
        this.resumenes = resumenes;
    }

    /** Los que calzan con {@code q}, por nombre, con paginas. Buscar no queda en la bitacora; abrir una ficha si. */
    @Transactional(readOnly = true)
    public Page<DeudorEncontradoResponse> buscar(String q, int pagina, int porPagina) {
        String texto = q == null ? "" : q.strip();
        if (texto.length() < MINIMO_PARA_BUSCAR) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Escribe al menos " + MINIMO_PARA_BUSCAR + " letras o numeros para buscar");
        }
        if (pagina < 0 || porPagina < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La página y su tamaño deben ser positivos");
        }
        PageRequest orden = PageRequest.of(pagina, Math.min(porPagina, MAXIMO_POR_PAGINA),
                Sort.by("fullName", "rut"));
        Page<Debtor> encontrados = deudores.buscar(comoRut(texto), escapar(texto), orden);
        Map<Long, List<DeudasDelDeudor>> porDeudor = encontrados.isEmpty() ? Map.of()
                : deudas.porDeudorYAcreedor(encontrados.getContent()).stream()
                        .collect(Collectors.groupingBy(DeudasDelDeudor::deudor));
        return encontrados.map(r -> {
            List<DeudasDelDeudor> filas = porDeudor.getOrDefault(r.getId(), List.of());
            return new DeudorEncontradoResponse(r.getRut(), r.getFullName(), r.getEmail(),
                    filas.stream().mapToLong(DeudasDelDeudor::deudas).sum(),
                    filas.stream().map(DeudasDelDeudor::acreedor).sorted().toList());
        });
    }

    /** La ficha de un deudor. Se anota en la bitacora solo si existe: un 404 no muestra datos de nadie. */
    @Transactional
    public FichaDeudorResponse ficha(String rut, String admin) {
        String normalizado = Rut.normalizar(rut);
        if (!Rut.esValido(normalizado)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUT no valido");
        }
        Debtor deudor = deudores.findByRut(normalizado)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay ningun deudor con ese RUT"));
        List<Debt> filas = deudas.findByDebtorOrderByUpdatedAtDesc(deudor);
        Map<Long, DebtSummaryResponse> resumen = resumenes.resumenes(filas);
        FichaDeudorResponse ficha = new FichaDeudorResponse(deudor.getRut(), deudor.getFullName(), deudor.getKind(),
                deudor.getEmail(), deudor.getPhone(), deudor.isReminders(),
                new FichaDeudorResponse.Correo(deudor.getEmailFailedAt(), deudor.getEmailFailure(),
                        deudor.getEmailRejections(), deudor.correoConProblemas()),
                filas.stream().map(d -> FichaDeudorResponse.Deuda.de(d, resumen.get(d.getId()))).toList(),
                contactos.findTop50ByDebtDebtorOrderByOccurredAtDesc(deudor).stream()
                        .map(FichaDeudorResponse.Contacto::de).toList());
        bitacora.save(AdminLog.de(admin, AdminLog.Action.deudor_consultado, null, deudor.getRut()));
        return ficha;
    }

    /** Una deuda sin datos del deudor, para el detalle de un pago en el panel (#435). No queda en la bitacora. */
    @Transactional(readOnly = true)
    public DeudaAdminResponse deuda(Long id) {
        return deudas.findById(id).map(DeudaAdminResponse::de)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay ninguna deuda con ese id"));
    }

    /**
     * Lo escrito como el comienzo de un RUT sin puntos, espacios ni guion
     * ("16.482.337" queda en "16482337"), o null si no lo parece.
     */
    static String comoRut(String texto) {
        String limpio = texto.replace(".", "").replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        return limpio.matches("\\d{" + MINIMO_PARA_BUSCAR + ",9}K?") ? limpio : null;
    }

    /** Para LIKE con escape '!': un % o un _ escrito se busca tal cual, no como comodin. */
    static String escapar(String texto) {
        return texto.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
