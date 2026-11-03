package com.petproject.payflow.service;

import com.petproject.payflow.api.dto.AccountResponse;
import com.petproject.payflow.api.dto.CreateAccountRequest;
import com.petproject.payflow.api.dto.PageResponse;
import com.petproject.payflow.api.dto.TransferResponse;
import com.petproject.payflow.api.error.AccountNotFoundException;
import com.petproject.payflow.api.error.ConflictException;
import com.petproject.payflow.config.CacheConfig;
import com.petproject.payflow.domain.Account;
import com.petproject.payflow.domain.Transfer;
import com.petproject.payflow.repository.AccountRepository;
import com.petproject.payflow.repository.TransferRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;

    public AccountService(AccountRepository accountRepository, TransferRepository transferRepository) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (accountRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Email already used",
                    "An account with email %s already exists".formatted(email));
        }
        Account account = Account.create(
                request.ownerName().trim(),
                email,
                request.currency(),
                request.initialBalance() == null ? ZERO : request.initialBalance());
        return toResponse(accountRepository.save(account));
    }

    @Cacheable(cacheNames = CacheConfig.ACCOUNTS_CACHE, key = "#accountId")
    @Transactional(readOnly = true)
    public AccountResponse getById(UUID accountId) {
        return accountRepository.findById(accountId)
                .map(AccountService::toResponse)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> history(UUID accountId, int page, int size) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        Page<Transfer> transfers = transferRepository.findHistory(accountId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.from(transfers.map(TransferResponse::from));
    }

    private static AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerName(),
                account.getEmail(),
                account.getStatus(),
                account.getBalance(),
                account.getCurrency(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
