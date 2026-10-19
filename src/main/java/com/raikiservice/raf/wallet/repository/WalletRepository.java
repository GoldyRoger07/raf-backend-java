package com.raikiservice.raf.wallet.repository;

import java.util.Optional;

import com.raikiservice.raf.wallet.domain.Wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    /**
     * Verifie l'existence sans poser de verrou ni charger l'entite.
     *
     * <p>Indispensable avant {@link #findByUserIdForUpdate} : sous InnoDB, une
     * lecture verrouillee sur une ligne absente pose un verrou d'intervalle qui
     * bloque toute insertion dans cette plage, y compris celle que l'on s'apprete
     * a faire. Et comme la requete renvoie un scalaire, elle ne place aucune copie
     * du portefeuille dans le cache de premier niveau.
     */
    boolean existsByUserId(Long userId);

    /**
     * Verrou pessimiste (SELECT ... FOR UPDATE) obligatoire avant toute ecriture
     * de solde.
     *
     * <p>Un verrou optimiste ne conviendrait pas ici : il detecte le conflit au
     * commit et impose de rejouer l'operation, ce qui sur un debit devient vite
     * une source de double comptage. Le verrou pessimiste serialise les acces au
     * portefeuille, qui sont rares et courts.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.user.id = :userId")
    Optional<Wallet> findByUserIdForUpdate(@Param("userId") Long userId);
}
