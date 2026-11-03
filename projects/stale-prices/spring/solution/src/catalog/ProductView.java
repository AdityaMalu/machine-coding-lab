package catalog;

import java.math.BigDecimal;

/** What the shop shows for one product, priced in the shopper's currency. */
public record ProductView(String sku, String name, String category, String currency, BigDecimal price) {}
