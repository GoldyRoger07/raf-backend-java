package com.raikiservice.raf.auth.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.domain.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByPhone(String phone);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPhone(String phone);

    /**
     * Recherche de comptes pour la gestion des roles : email ou nom (term), ou
     * telephone (digits) ; staffOnly restreint aux comptes du personnel (moderateur, administrateur,
     * super-administrateur).
     *
     * <p>Filtres facultatifs en plus : un role precis et un statut.
     */
    @Query("select u from User u where "
            + "(lower(u.email) like :term or lower(concat(u.firstName, ' ', u.lastName)) like :term "
            + " or u.phone like :digits) "
            + "and (:staffOnly = false or exists (select r from u.roles r "
            + " where r.name in (com.raikiservice.raf.auth.domain.RoleName.MODERATOR, com.raikiservice.raf.auth.domain.RoleName.ADMIN, com.raikiservice.raf.auth.domain.RoleName.SUPER_ADMIN))) "
            + "and (:role is null or exists (select r2 from u.roles r2 where r2.name = :role)) "
            + "and (:status is null or u.status = :status)")
    Page<User> searchAccounts(@Param("term") String term, @Param("digits") String digits,
            @Param("staffOnly") boolean staffOnly, @Param("role") RoleName role,
            @Param("status") UserStatus status, Pageable pageable);

    /** Super-administrateurs actifs : il doit toujours en rester au moins un. */
    @Query("select count(distinct u) from User u join u.roles r "
            + "where r.name = com.raikiservice.raf.auth.domain.RoleName.SUPER_ADMIN "
            + "and u.status = com.raikiservice.raf.auth.domain.UserStatus.ACTIVE")
    long countActiveSuperAdmins();

    /** Comptes actifs portant l'un de ces roles (destinataires des alertes d'administration). */
    @Query("select distinct u.id from User u join u.roles r where r.name in :roles "
            + "and u.status = com.raikiservice.raf.auth.domain.UserStatus.ACTIVE")
    List<Long> findIdsByRoles(@Param("roles") Collection<RoleName> roles);

    /**
     * Joueurs actifs, sans role d'equipe (moderateur, admin) : memes destinataires
     * qu'une annonce a tous. testOnly restreint aux comptes de test.
     */
    @Query(value = "SELECT u.id FROM users u WHERE u.status = 'ACTIVE' "
            + "AND (:testOnly = FALSE OR u.is_test = TRUE) "
            + "AND NOT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
            + "WHERE ur.user_id = u.id AND r.name IN ('MODERATOR', 'ADMIN', 'SUPER_ADMIN'))",
            nativeQuery = true)
    List<Long> findActivePlayerIds(@Param("testOnly") boolean testOnly);
}
