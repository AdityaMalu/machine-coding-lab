package shop;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bound from the shop.* keys in application.properties, and checked when the app starts. */
@ConfigurationProperties("shop")
@Validated
public record ShopProperties(
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @Min(1) @Max(100) int maxItemsPerOrder,
        @NotNull @DecimalMin("0") @DecimalMax("0.5") BigDecimal taxRate) {}
