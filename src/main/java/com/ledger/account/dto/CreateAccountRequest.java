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
        @PositiveOrZero @Max(999_999_999_999_999L) Long overdraftLimit) {
}
