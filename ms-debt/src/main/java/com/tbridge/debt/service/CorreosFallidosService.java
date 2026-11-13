package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.CorreosFallidosResponse;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.EmpresaDelDeudor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Los correos a deudores que no salieron, para la pantalla Operacion del panel
 * (#437): a quien le fallo, de que empresa es, por que y cuantos rechazos
 * seguidos lleva la direccion.
 *
 * <p>La empresa sale de las deudas del deudor, sin repetir: un mismo deudor
 * puede deberle a mas de una.
 */
@Service
public class CorreosFallidosService {

    private final DebtorRepository deudores;
    private final DebtRepository deudas;

    public CorreosFallidosService(DebtorRepository deudores, DebtRepository deudas) {
        this.deudores = deudores;
        this.deudas = deudas;
    }

    @Transactional(readOnly = true)
    public CorreosFallidosResponse conCorreoFallido() {
        List<Debtor> fallidos = deudores.conCorreoFallido();
        if (fallidos.isEmpty()) {
            return new CorreosFallidosResponse(0, List.of());
        }
        Map<Long, List<String>> empresas = new HashMap<>();
        for (EmpresaDelDeudor empresa : deudas.empresasDeDeudores(fallidos.stream().map(Debtor::getId).toList())) {
            empresas.computeIfAbsent(empresa.deudor(), k -> new ArrayList<>()).add(empresa.empresa());
        }
        return new CorreosFallidosResponse(fallidos.size(), fallidos.stream()
                .map(deudor -> new CorreosFallidosResponse.Deudor(deudor.getRut(), deudor.getFullName(),
                        deudor.getEmail(), empresas.getOrDefault(deudor.getId(), List.of()),
                        deudor.getEmailFailedAt(), deudor.getEmailFailure(), deudor.getEmailRejections(),
                        deudor.correoConProblemas()))
                .toList());
    }
}
