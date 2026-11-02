package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DebtRepository extends JpaRepository<Debt, Long> {

    /** Las deudas de un mandato que siguen en gestion: las que se cobran aunque el mandato termine (#234). */
    long countByMandateAndStatusIn(Mandate mandate, Collection<Debt.Status> estados);

    /**
     * La cartera que opera una organizacion en DataBridge: la suya como
     * acreedora y la que ella misma entrego como agencia.
     *
     * <p>En la cadena Patrimonio -> APOFYX -> DataBridge, quien trabaja aqui
     * es APOFYX. Patrimonio nunca entra a DataBridge (contrato, seccion 1), y
     * con solo "donde soy acreedor" el personal de APOFYX veia una cartera
     * vacia.
     *
     * <p>Nunca findAll(): el modelo anterior hacia eso y cualquier acreedor
     * veia las deudas de todos.
     */
    @Query("select d from Debt d where d.creditor = :org or d.lastBatch.sender = :org "
            + "order by d.updatedAt desc")
    List<Debt> carteraDe(@Param("org") Organization org);

    /**
     * Cuantas deudas hay, por acreedor, por quien las entrego y por estado.
     *
     * <p>Para los totales del panel de administracion. Son dos consultas para
     * todas las empresas, y ninguna lee una deuda: el admin de DataBridge ve
     * cuanto hay en cada cartera, nunca que hay adentro.
     */
    @Query("select new com.tbridge.debt.repository.ConteoDeDeudas("
            + "d.creditor.id, d.lastBatch.sender.id, d.status, count(d)) "
            + "from Debt d group by d.creditor.id, d.lastBatch.sender.id, d.status")
    List<ConteoDeDeudas> contarPorEmpresa();

    /** Lo que falta por cobrar: las cuotas pendientes, por acreedor, por quien las entrego y por moneda. */
    @Query("select new com.tbridge.debt.repository.SaldoPendiente("
            + "d.creditor.id, d.lastBatch.sender.id, d.currency, sum(i.amount)) "
            + "from Installment i join i.debt d "
            + "where i.status = com.tbridge.debt.model.Installment.Status.pending "
            + "group by d.creditor.id, d.lastBatch.sender.id, d.currency")
    List<SaldoPendiente> saldoPorEmpresa();

    List<Debt> findByDebtorOrderByUpdatedAtDesc(Debtor debtor);

    List<Debt> findByCampaign(Campaign campaign);

    /**
     * Las deudas en cobranza por las que todavia no se le escribio nada al
     * deudor: su invitacion fallo, la ley la dejo para despues, o el deudor no
     * tenia correo y una cartera se lo trajo. Sin las de una direccion con
     * problemas, que esperan otra, ni las de una empresa suspendida (#152).
     */
    @Query("""
            select d from Debt d join fetch d.debtor r join fetch d.creditor
             where d.status = com.tbridge.debt.model.Debt.Status.open
               and r.email is not null and r.email <> '' and r.emailRejections < :rechazos
               and not exists (select 1 from Contact c where c.debt = d)
               and d.creditor.status = com.tbridge.debt.model.Organization.Status.active
               and not exists (select 1 from Campaign k where k = d.campaign and k.agency.status = com.tbridge.debt.model.Organization.Status.suspended)
               and not exists (select 1 from Mandate m where m = d.mandate and m.agency.status = com.tbridge.debt.model.Organization.Status.suspended)
             order by d.id""")
    List<Debt> sinInvitar(@Param("rechazos") int rechazos);

    List<Debt> findByLastBatch(Batch lote);

    Optional<Debt> findByCreditorAndExternalId(Organization creditor, String externalId);
}
