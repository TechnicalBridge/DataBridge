package com.tbridge.auth.repository;

import com.tbridge.auth.model.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    Optional<StaffUser> findByEmailIgnoreCase(String email);

    boolean existsByRole(StaffUser.Role role);
}
