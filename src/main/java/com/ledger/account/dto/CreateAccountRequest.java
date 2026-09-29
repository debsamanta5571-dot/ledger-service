package com.ledger.account.dto;

import com.ledger.account.AccountType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateAccountRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO 4217 code") String currency,
        @NotNull AccountType type,
        @PositiveOrZero @Max(999_999_999_999_999L) Long overdraftLimit,
        // Admins only: open the account for someone else (e.g. a customer just given a sign-in). Omit for yourself.
        @Pattern(regexp = "user:[A-Za-z0-9-]{1,100}", message = "must be an identity-service user, user:<id>")
        String ownerId,
        @Size(max = 200) String ownerName) {
}
