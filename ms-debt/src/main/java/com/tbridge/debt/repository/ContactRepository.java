package com.tbridge.debt.repository;

import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    /**
     * Cuando se le escribio a un deudor, por cualquiera de sus deudas: para no
     * escribirle mas de lo que deja la ley.
     */
    @Query("select c.occurredAt from Contact c where c.debt.debtor = :deudor and c.occurredAt > :desde")
    List<Instant> cuandoSeLeEscribio(@Param("deudor") Debtor deudor, @Param("desde") Instant desde);

    /** Cuantos toques de la campana lleva cada una de esas deudas. Las que no llevan ninguno no vienen. */
    @Query("select new com.tbridge.debt.repository.ToquesHechos(c.debt.id, count(c)) "
            + "from Contact c where c.campaign = :campana and c.debt in :deudas group by c.debt.id")
    List<ToquesHechos> toquesPorDeuda(@Param("campana") Campaign campana, @Param("deudas") Collection<Debt> deudas);

    /** La ultima vez que se le escribio por cada una de esas deudas. Las que nunca, no vienen. */
    @Query("select new com.tbridge.debt.repository.UltimoContacto(c.debt.id, max(c.occurredAt)) "
            + "from Contact c where c.debt in :deudas group by c.debt.id")
    List<UltimoContacto> ultimoPorDeuda(@Param("deudas") Collection<Debt> deudas);

    /** Todo lo que se escribio por esas deudas: el primer paso del embudo de una campana. */
    long countByDebtIn(Collection<Debt> deudas);

    /**
     * Los contactos de las deudas de cada campana desde {@code semana}, y
     * cuantos desde {@code hoy}: la invitacion, los toques, los recordatorios y
     * los reenvios, como los cuenta el limite de la ley. Para el panel (#441).
     */
    @Query("select new com.tbridge.debt.repository.ContactosPorCampana(d.campaign.id, "
            + "coalesce(sum(case when c.occurredAt >= :hoy then 1 else 0 end), 0), count(c)) "
            + "from Contact c join c.debt d where d.campaign is not null and c.occurredAt >= :semana "
            + "group by d.campaign.id")
    List<ContactosPorCampana> porCampanaDesde(@Param("semana") Instant semana, @Param("hoy") Instant hoy);

    /** Los de una deuda, para su historia, con la campana y la cuota de cada uno. */
    @EntityGraph(attributePaths = {"campaign", "installment"})
    List<Contact> findByDebtOrderByOccurredAtAsc(Debt debt);

    /** Los ultimos 50 correos a un deudor, en todas sus deudas: la ficha del panel (#434). */
    @EntityGraph(attributePaths = {"debt", "debt.creditor", "campaign"})
    List<Contact> findTop50ByDebtDebtorOrderByOccurredAtDesc(Debtor deudor);
}
