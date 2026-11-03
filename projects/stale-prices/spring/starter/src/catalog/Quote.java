package catalog;

import java.math.BigDecimal;

/** The price for a quantity, after the bulk discount. */
public record Quote(String sku, String currency, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal total) {}
