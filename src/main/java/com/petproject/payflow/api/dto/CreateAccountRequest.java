package com.petproject.payflow.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * {@code initialBalance} exists only for demo purposes. A real payment system
 * would fund accounts through a separate deposit flow with its own controls.
 */
public record CreateAccountRequest(
        @NotBlank @Size(max = 120) String ownerName,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code in upper case, e.g. USD")
        String currency,
        @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal initialBalance) {
}
