package com.raikiservice.raf.auth.repository;

import java.util.Optional;

import com.raikiservice.raf.auth.domain.Role;
import com.raikiservice.raf.auth.domain.RoleName;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(RoleName name);
}
