package com.petproject.payflow.repository;

import com.petproject.payflow.domain.Transfer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            select t from Transfer t
            where t.sourceAccountId = :accountId or t.targetAccountId = :accountId
            """)
    Page<Transfer> findHistory(@Param("accountId") UUID accountId, Pageable pageable);
}
