package com.tbridge.debt.repository;

import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    @Query("select new com.tbridge.debt.repository.ConteoPorCampana("
            + "c.id, c.name, a.tradeName, cr.tradeName, c.status, c.startsOn, c.endsOn, count(d), "
            + "coalesce(sum(case when d.status = com.tbridge.debt.model.Debt.Status.open then 1 else 0 end), 0)) "
            + "from Campaign c join c.agency a join c.creditor cr left join Debt d on d.campaign = c "
            + "group by c.id, c.name, a.tradeName, cr.tradeName, c.status, c.startsOn, c.endsOn order by c.name")
    List<ConteoPorCampana> contarParaAdmin();

    Optional<Campaign> findByAgencyAndExternalId(Organization agency, String externalId);

    List<Campaign> findByAgencyAndCreditorOrderByStartsOnDesc(Organization agency, Organization creditor);

    List<Campaign> findByStatus(Campaign.Status status);

    /** Las que gestiona una empresa: como agencia, o como acreedor que cobra sin agencia. */
    List<Campaign> findByAgencyOrderByStartsOnDesc(Organization agency);
}
