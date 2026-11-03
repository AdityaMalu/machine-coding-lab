package catalog;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

public record PriceUpdate(@NotNull BigDecimal priceUsd) {}
