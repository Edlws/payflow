package com.petproject.payflow.api;

import com.petproject.payflow.api.dto.AccountResponse;
import com.petproject.payflow.api.dto.CreateAccountRequest;
import com.petproject.payflow.api.dto.PageResponse;
import com.petproject.payflow.api.dto.TransferResponse;
import com.petproject.payflow.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Validated
@Tag(name = "Accounts", description = "Account management and transfer history")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Create an account (initial balance is for demo purposes only)")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse account = accountService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id())).body(account);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get an account by id (cached in Redis)")
    public AccountResponse get(@PathVariable UUID accountId) {
        return accountService.getById(accountId);
    }

    @GetMapping("/{accountId}/transfers")
    @Operation(summary = "List transfers where the account is the source or the target")
    public PageResponse<TransferResponse> history(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return accountService.history(accountId, page, size);
    }
}
