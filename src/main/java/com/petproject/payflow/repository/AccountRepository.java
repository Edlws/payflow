package com.petproject.payflow.repository;

import com.petproject.payflow.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Loads both accounts with row-level locks in a deterministic order.
     * Ordering by id prevents deadlocks when two transfers between the same
     * pair of accounts run concurrently in opposite directions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> findAllForUpdate(@Param("ids") Collection<UUID> ids);

    boolean existsByEmailIgnoreCase(String email);
}
