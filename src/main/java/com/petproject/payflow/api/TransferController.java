package com.petproject.payflow.api;

import com.petproject.payflow.api.dto.TransferRequest;
import com.petproject.payflow.api.dto.TransferResponse;
import com.petproject.payflow.domain.Transfer;
import com.petproject.payflow.domain.TransferStatus;
import com.petproject.payflow.service.TransferOutcome;
import com.petproject.payflow.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@Validated
@Tag(name = "Transfers", description = "Idempotent money transfers between accounts")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @Operation(summary = "Create a transfer",
            description = """
                    The Idempotency-Key header makes the operation retry-safe: repeating the
                    request with the same key returns the original result instead of moving
                    money twice. Business rejections return 422 with the persisted transfer id.
                    """)
    public ResponseEntity<?> create(
            @Parameter(description = "Client-generated unique key, 8-64 characters", required = true)
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9._:-]{8,64}", message = "must be 8-64 characters: letters, digits, . _ : -")
            String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        TransferOutcome outcome = transferService.transfer(idempotencyKey, request);
        Transfer transfer = outcome.transfer();

        if (transfer.getStatus() == TransferStatus.FAILED) {
            return ResponseEntity.unprocessableEntity().body(failedTransferProblem(transfer));
        }

        TransferResponse body = TransferResponse.from(transfer);
        if (outcome.replayed()) {
            return ResponseEntity.ok()
                    .header("Idempotent-Replayed", "true")
                    .body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + transfer.getId())).body(body);
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get a transfer by id")
    public TransferResponse get(@PathVariable UUID transferId) {
        return transferService.getById(transferId);
    }

    private static ProblemDetail failedTransferProblem(Transfer transfer) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, transfer.getFailureMessage());
        problem.setTitle("Transfer failed");
        problem.setType(URI.create("urn:payflow:error:transfer-failed"));
        problem.setProperty("transferId", transfer.getId());
        problem.setProperty("reason", transfer.getFailureReason());
        problem.setProperty("timestamp", Instant.now());
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
