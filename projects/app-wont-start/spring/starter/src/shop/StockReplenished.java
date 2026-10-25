package shop;

/** Sent to OrderService after a restock. */
public record StockReplenished(String sku) {}
