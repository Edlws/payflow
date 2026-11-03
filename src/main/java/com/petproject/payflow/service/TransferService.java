package com.petproject.payflow.service;

import com.petproject.payflow.api.dto.TransferRequest;
import com.petproject.payflow.api.dto.TransferResponse;
import com.petproject.payflow.api.error.DuplicateRequestException;
import com.petproject.payflow.api.error.TransferNotFoundException;
import com.petproject.payflow.config.CacheConfig;
import com.petproject.payflow.domain.Transfer;
import com.petproject.payflow.domain.TransferStatus;
import com.petproject.payflow.repository.TransferRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates a transfer: idempotency replay, rate limiting, transactional execution,
 * and cache invalidation. The transfer itself is committed by {@link TransferProcessor};
 * cache eviction happens after commit so the cache never holds a pre-transfer balance.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferProcessor processor;
    private final IdempotencyService idempotencyService;
    private final RedisRateLimiter rateLimiter;
    private final CacheManager cacheManager;
    private final TransferRepository transferRepository;

    public TransferService(TransferProcessor processor, IdempotencyService idempotencyService,
                           RedisRateLimiter rateLimiter, CacheManager cacheManager,
                           TransferRepository transferRepository) {
        this.processor = processor;
        this.idempotencyService = idempotencyService;
        this.rateLimiter = rateLimiter;
        this.cacheManager = cacheManager;
        this.transferRepository = transferRepository;
    }

    @Transactional(readOnly = true)
    public TransferResponse getById(UUID transferId) {
        return transferRepository.findById(transferId)
                .map(TransferResponse::from)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
    }

    public TransferOutcome transfer(String idempotencyKey, TransferRequest request) {
        Optional<Transfer> previous = idempotencyService.findResult(idempotencyKey);
        if (previous.isPresent()) {
            log.info("Replaying transfer {} for Idempotency-Key {}", previous.get().getId(), idempotencyKey);
            return TransferOutcome.replay(previous.get());
        }

        rateLimiter.checkTransferAllowed(request.sourceAccountId());

        if (!idempotencyService.tryLock(idempotencyKey)) {
            return idempotencyService.findResult(idempotencyKey)
                    .map(TransferOutcome::replay)
                    .orElseThrow(() -> new DuplicateRequestException(idempotencyKey));
        }

        try {
            TransferOutcome outcome = processor.execute(idempotencyKey, request);
            idempotencyService.complete(idempotencyKey, outcome.transfer().getId());
            if (outcome.transfer().getStatus() == TransferStatus.COMPLETED) {
                evictAccounts(outcome.transfer());
            }
            return outcome;
        } catch (DataIntegrityViolationException exception) {
            // Another instance inserted the same idempotency key first.
            idempotencyService.release(idempotencyKey);
            log.warn("Idempotency race for key {}: {}", idempotencyKey,
                    exception.getMostSpecificCause().getMessage());
            return idempotencyService.findResult(idempotencyKey)
                    .map(TransferOutcome::replay)
                    .orElseThrow(() -> new DuplicateRequestException(idempotencyKey));
        } catch (RuntimeException exception) {
            idempotencyService.release(idempotencyKey);
            throw exception;
        }
    }

    private void evictAccounts(Transfer transfer) {
        Cache cache = cacheManager.getCache(CacheConfig.ACCOUNTS_CACHE);
        if (cache == null) {
            return;
        }
        try {
            cache.evict(transfer.getSourceAccountId());
            cache.evict(transfer.getTargetAccountId());
        } catch (RuntimeException exception) {
            // The money has already moved; a stale cache entry will expire by TTL.
            log.warn("Failed to evict accounts cache after transfer {}: {}",
                    transfer.getId(), exception.getMessage());
        }
    }
}
