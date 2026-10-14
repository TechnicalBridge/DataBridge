package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.util.Hash;
import com.tbridge.debt.util.Rut;
import com.tbridge.debt.dto.response.ClaveEmitidaResponse;
import com.tbridge.debt.dto.response.ClaveResponse;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.ApiKey;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.ApiKeyRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Las credenciales de maquina del contrato de integracion.
 *
 * <p>La clave se muestra una sola vez, al emitirla. En la base queda su huella
 * SHA-256, asi que ni DataBridge puede recordarsela a nadie: si se pierde, se
 * emite otra y se revoca la anterior.
 */
@Service
public class ApiKeyService {

    private static final SecureRandom AZAR = new SecureRandom();

    private final ApiKeyRepository claves;
    private final OrganizationRepository organizations;

    public ApiKeyService(ApiKeyRepository claves, OrganizationRepository organizations) {
        this.claves = claves;
        this.organizations = organizations;
    }

    /** Emite una clave para la organizacion de ese RUT. */
    @Transactional
    public ClaveEmitidaResponse emitirPara(String rutCrudo, String nombre) {
        String rut = Rut.normalizar(rutCrudo);
        Organization organizacion = organizations.findByRut(rut).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "No hay ninguna organizacion con RUT " + rut));
        return emitir(organizacion, nombre);
    }

    /** Las claves de una organizacion, las activas y las revocadas. */
    @Transactional(readOnly = true)
    public List<ClaveResponse> listar(Organization organizacion) {
        return claves.findByOrganizationOrderByCreatedAtDesc(organizacion).stream().map(ClaveResponse::from).toList();
    }

    /**
     * Revoca una clave de la organizacion. No se borra: queda para saber que
     * existio y hasta cuando se uso. Revocar una ya revocada no hace nada.
     */
    @Transactional
    public ClaveResponse revocar(Organization organizacion, Long id) {
        ApiKey clave = claves.findById(id)
                .filter(c -> c.getOrganization().getId().equals(organizacion.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Esa clave no es de tu organizacion"));
        if (clave.getRevokedAt() == null) {
            clave.setRevokedAt(Instant.now());
            claves.save(clave);
        }
        return ClaveResponse.from(clave);
    }

    /** Emite una clave para una organizacion ya resuelta. Se devuelve una sola vez. */
    @Transactional
    public ClaveEmitidaResponse emitir(Organization organizacion, String nombre) {
        byte[] azar = new byte[32];
        AZAR.nextBytes(azar);
        String clave = "tbk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(azar);

        ApiKey registro = new ApiKey();
        registro.setOrganization(organizacion);
        registro.setName(nombre);
        registro.setKeyHash(Hash.sha256(clave));
        registro.setPrefix(clave.substring(0, 12));
        claves.save(registro);
        return new ClaveEmitidaResponse(clave, registro.getPrefix(), organizacion.getTradeName(),
                "Guardala ahora: no se puede volver a mostrar");
    }

    /**
     * La organizacion duena de la clave que viene en {@code Authorization:
     * Bearer}. El emisor sale de la clave, nunca del cuerpo: un campo del
     * cuerpo se puede falsificar, la clave no.
     */
    @Transactional
    public Organization autenticar(String autorizacion) {
        String clave = autorizacion != null && autorizacion.startsWith("Bearer ")
                ? autorizacion.substring(7).trim() : "";
        if (clave.isBlank()) {
            throw noAutorizado();
        }
        return claves.findByKeyHashAndRevokedAtIsNull(Hash.sha256(clave)).map(registro -> {
            Organization org = registro.getOrganization();
            if (org.getStatus() == Organization.Status.suspended) {
                throw new CarteraInvalida("empresa_suspendida",
                        "La empresa esta suspendida: sus claves de API no pueden operar", 403);
            }
            registro.setLastUsedAt(Instant.now());
            claves.save(registro);
            return org;
        }).orElseThrow(ApiKeyService::noAutorizado);
    }

    private static CarteraInvalida noAutorizado() {
        return new CarteraInvalida("no_autorizado", "Clave de API invalida", 401);
    }
}
