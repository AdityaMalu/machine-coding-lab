package catalog;

import java.math.BigDecimal;

/** A catalog entry. Prices are stored in USD. */
public record Product(String sku, String name, String category, BigDecimal priceUsd) {}
