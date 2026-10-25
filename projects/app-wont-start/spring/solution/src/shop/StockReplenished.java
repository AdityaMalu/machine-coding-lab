package shop;

/** Published by InventoryService after a restock, so others can react without InventoryService knowing them. */
public record StockReplenished(String sku) {}
