package com.tbridge.auth.repository;

import com.tbridge.auth.model.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    Optional<StaffUser> findByEmailIgnoreCase(String email);

    boolean existsByRole(StaffUser.Role role);

    /** Las personas de una empresa, por nombre. */
    List<StaffUser> findByOrgRutOrderByFullNameAsc(String orgRut);

    /** Las personas de un rol, por nombre: los admin de DataBridge. */
    List<StaffUser> findByRoleOrderByFullNameAsc(StaffUser.Role role);
}
