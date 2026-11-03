package com.petproject.payflow.service;

import com.petproject.payflow.config.AppProperties;
import com.petproject.payflow.domain.Transfer;
import com.petproject.payflow.repository.TransferRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Two-layer idempotency:
 * <ul>
 *   <li>Redis short-lived lock — stops concurrent duplicates across instances fast;</li>
 *   <li>Redis result cache — serves replays without touching the database;</li>
 *   <li>unique index on transfers.idempotency_key — the final source of truth.</li>
 * </ul>
 * Redis failures degrade to database-only protection rather than failing requests.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String RESULT_PREFIX = "payflow:idempotency:result:";
    private static final String LOCK_PREFIX = "payflow:idempotency:lock:";

    private final StringRedisTemplate redisTemplate;
    private final TransferRepository transferRepository;
    private final AppProperties properties;

    public IdempotencyService(StringRedisTemplate redisTemplate, TransferRepository transferRepository,
                              AppProperties properties) {
        this.redisTemplate = redisTemplate;
        this.transferRepository = transferRepository;
        this.properties = properties;
    }

    public Optional<Transfer> findResult(String idempotencyKey) {
        try {
            String cachedId = redisTemplate.opsForValue().get(RESULT_PREFIX + idempotencyKey);
            if (cachedId != null) {
                try {
                    return transferRepository.findById(UUID.fromString(cachedId));
                } catch (IllegalArgumentException ignored) {
                    log.warn("Ignoring malformed idempotency cache entry for key {}", idempotencyKey);
                }
            }
        } catch (DataAccessException exception) {
            log.warn("Idempotency lookup in Redis failed for key {}: {}",
                    idempotencyKey, exception.getMessage());
        }

        Optional<Transfer> transfer = transferRepository.findByIdempotencyKey(idempotencyKey);
        transfer.ifPresent(value -> cacheResult(idempotencyKey, value.getId()));
        return transfer;
    }

    /**
     * @return {@code false} when another request with the same key is being processed
     */
    public boolean tryLock(String idempotencyKey) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(LOCK_PREFIX + idempotencyKey, "1", properties.idempotency().lockTtl());
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException exception) {
            log.warn("Idempotency lock in Redis failed for key {}; relying on the database unique index: {}",
                    idempotencyKey, exception.getMessage());
            return true;
        }
    }

    public void complete(String idempotencyKey, UUID transferId) {
        cacheResult(idempotencyKey, transferId);
        release(idempotencyKey);
    }

    public void release(String idempotencyKey) {
        try {
            redisTemplate.delete(LOCK_PREFIX + idempotencyKey);
        } catch (DataAccessException exception) {
            log.warn("Failed to release idempotency lock for key {}: {}",
                    idempotencyKey, exception.getMessage());
        }
    }

    private void cacheResult(String idempotencyKey, UUID transferId) {
        try {
            redisTemplate.opsForValue().set(RESULT_PREFIX + idempotencyKey, transferId.toString(),
                    properties.idempotency().resultTtl());
        } catch (DataAccessException exception) {
            log.warn("Failed to cache idempotency result for key {}: {}",
                    idempotencyKey, exception.getMessage());
        }
    }
}
