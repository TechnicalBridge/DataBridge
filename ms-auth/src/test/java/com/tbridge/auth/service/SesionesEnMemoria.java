package com.tbridge.auth.service;

import com.tbridge.auth.model.Session;
import com.tbridge.auth.repository.SessionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Un SessionRepository que guarda en una lista, para probar las reglas de la
 * sesion sin levantar una base.
 */
final class SesionesEnMemoria {

    final List<Session> filas = new ArrayList<>();
    final SessionRepository repositorio = mock(SessionRepository.class);

    SesionesEnMemoria() {
        when(repositorio.save(any())).thenAnswer(llamada -> {
            Session sesion = llamada.getArgument(0);
            if (!filas.contains(sesion)) {
                filas.add(sesion);
            }
            return sesion;
        });
        when(repositorio.findByRefreshHash(any())).thenAnswer(llamada -> buscar(llamada.getArgument(0)));
        when(repositorio.bloquearPorHuella(any())).thenAnswer(llamada -> buscar(llamada.getArgument(0)));
        when(repositorio.revocarFamilia(any(), any())).thenAnswer(llamada -> {
            String familia = llamada.getArgument(0);
            Instant ahora = llamada.getArgument(1);
            int revocadas = 0;
            for (Session sesion : filas) {
                if (sesion.getFamilyId().equals(familia) && sesion.getRevokedAt() == null) {
                    sesion.setRevokedAt(ahora);
                    revocadas++;
                }
            }
            return revocadas;
        });
        when(repositorio.revocarDeCorreo(any(), any())).thenAnswer(llamada -> {
            String correo = llamada.getArgument(0);
            Instant ahora = llamada.getArgument(1);
            int revocadas = 0;
            for (Session sesion : filas) {
                if (correo.equalsIgnoreCase(sesion.getEmail()) && sesion.getRevokedAt() == null) {
                    sesion.setRevokedAt(ahora);
                    revocadas++;
                }
            }
            return revocadas;
        });
    }

    private Optional<Session> buscar(String huella) {
        return filas.stream().filter(s -> s.getRefreshHash().equals(huella)).findFirst();
    }
}
