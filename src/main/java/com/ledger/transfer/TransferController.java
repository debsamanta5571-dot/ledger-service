package com.ledger.transfer;

import com.ledger.api.Caller;
import com.ledger.api.InvalidRequestException;
import com.ledger.transfer.dto.TransferRequest;
import com.ledger.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    static final int MAX_KEY_LENGTH = 255;

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @Operation(summary = "Transfer value between two accounts",
            description = "Idempotent: repeating a request with the same Idempotency-Key and body returns the "
                    + "original response (with header Idempotent-Replayed: true); the same key with a different "
                    + "body returns 409.")
    @ApiResponse(responseCode = "201", description = "Transfer posted (or replayed)")
    @ApiResponse(responseCode = "404", description = "Unknown account")
    @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different body")
    @ApiResponse(responseCode = "422", description = "Insufficient funds, or currency/type mismatch")
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication caller,
            @Valid @RequestBody TransferRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidRequestException(
                    "Idempotency-Key must be between 1 and " + MAX_KEY_LENGTH + " characters");
        }
        TransferResult result = service.transfer(Caller.id(caller), idempotencyKey, request);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatusCode.valueOf(result.status()))
                .location(URI.create("/accounts/" + result.body().fromAccountId() + "/statement"));
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.body());
    }

}
