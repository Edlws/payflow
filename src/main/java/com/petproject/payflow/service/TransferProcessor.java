package com.petproject.payflow.service;

import com.petproject.payflow.api.dto.TransferRequest;
import com.petproject.payflow.api.error.AccountNotFoundException;
import com.petproject.payflow.api.error.SameAccountTransferException;
import com.petproject.payflow.domain.Account;
import com.petproject.payflow.domain.AccountStatus;
import com.petproject.payflow.domain.Transfer;
import com.petproject.payflow.domain.TransferStatus;
import com.petproject.payflow.messaging.OutboxWriter;
import com.petproject.payflow.repository.AccountRepository;
import com.petproject.payflow.repository.TransferRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes a transfer inside one database transaction:
 * lock accounts -> write transfer -> move money -> write outbox event.
 * Business rejections (blocked account, insufficient funds, currency mismatch) are
 * persisted as FAILED transfers with a corresponding event instead of being thrown,
 * so the client gets an auditable result.
 */
@Service
public class TransferProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final OutboxWriter outboxWriter;

    public TransferProcessor(AccountRepository accountRepository, TransferRepository transferRepository,
                             OutboxWriter outboxWriter) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public TransferOutcome execute(String idempotencyKey, TransferRequest request) {
        UUID sourceId = request.sourceAccountId();
        UUID targetId = request.targetAccountId();
        if (sourceId.equals(targetId)) {
            throw new SameAccountTransferException();
        }

        List<Account> locked = accountRepository.findAllForUpdate(List.of(sourceId, targetId));
        Account source = findAccount(locked, sourceId);
        Account target = findAccount(locked, targetId);

        BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        Transfer transfer = Transfer.pending(idempotencyKey, sourceId, targetId, amount, source.getCurrency());
        // Flush early so a duplicate idempotency key fails before money is moved.
        transferRepository.saveAndFlush(transfer);

        Optional<Failure> failure = validate(source, target, amount);
        if (failure.isPresent()) {
            Failure reason = failure.get();
            transfer.fail(reason.code(), reason.message());
            outboxWriter.appendTransferEvent(transfer);
            log.info("Transfer {} failed: {} ({})", transfer.getId(), reason.code(), reason.message());
            return TransferOutcome.created(transfer);
        }

        source.debit(amount);
        target.credit(amount);
        transfer.complete();
        outboxWriter.appendTransferEvent(transfer);

        log.info("Transfer {} completed: {} -> {} {} {}",
                transfer.getId(), sourceId, targetId, amount, transfer.getCurrency());
        return TransferOutcome.created(transfer);
    }

    private static Account findAccount(List<Account> accounts, UUID accountId) {
        return accounts.stream()
                .filter(account -> account.getId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private static Optional<Failure> validate(Account source, Account target, BigDecimal amount) {
        if (source.getStatus() != AccountStatus.ACTIVE) {
            return Optional.of(new Failure("SOURCE_NOT_ACTIVE", "Source account is not active"));
        }
        if (target.getStatus() != AccountStatus.ACTIVE) {
            return Optional.of(new Failure("TARGET_NOT_ACTIVE", "Target account is not active"));
        }
        if (!source.getCurrency().equals(target.getCurrency())) {
            return Optional.of(new Failure("CURRENCY_MISMATCH",
                    "Source currency %s does not match target currency %s"
                            .formatted(source.getCurrency(), target.getCurrency())));
        }
        if (source.getBalance().compareTo(amount) < 0) {
            return Optional.of(new Failure("INSUFFICIENT_FUNDS",
                    "Insufficient funds: available %s %s".formatted(source.getBalance(), source.getCurrency())));
        }
        return Optional.empty();
    }

    private record Failure(String code, String message) {
    }
}
