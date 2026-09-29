package com.ledger.transfer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Moves {@code amount} (minor units) of value from one account to another of the same type and currency. */
public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @Positive @Max(999_999_999_999_999L) Long amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO 4217 code") String currency,
        @Size(max = 255) String description) {
}
