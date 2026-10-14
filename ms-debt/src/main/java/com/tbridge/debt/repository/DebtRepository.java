package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DebtRepository extends JpaRepository<Debt, Long> {

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

    List<Debt> findByLastBatch(Batch lote);

    Optional<Debt> findByCreditorAndExternalId(Organization creditor, String externalId);
}
