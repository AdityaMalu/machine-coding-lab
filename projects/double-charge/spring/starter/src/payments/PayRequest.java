package payments;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body of POST /payments: pay for an order line. */
public record PayRequest(
        @NotBlank String orderId,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
        @Min(1) int quantity,
        @NotBlank @Email String email) {}
