package shop;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    record Item(String name, BigDecimal price, int stock) {}

    private final Map<String, Item> items = new ConcurrentHashMap<>();
    private final OrderService orders;

    public InventoryService(OrderService orders) {
        this.orders = orders;
        addItem("apple", "Apple", new BigDecimal("30.00"), 10);
        addItem("bread", "Bread", new BigDecimal("45.00"), 0);
    }

    public void addItem(String sku, String name, BigDecimal price, int stock) {
        items.put(sku, new Item(name, price, stock));
    }

    public BigDecimal price(String sku) {
        return item(sku).price();
    }

    public int stock(String sku) {
        return item(sku).stock();
    }

    /** Takes quantity units if that many are in stock. */
    public boolean reserve(String sku, int quantity) {
        Item it = item(sku);
        if (it.stock() < quantity) return false;
        items.put(sku, new Item(it.name(), it.price(), it.stock() - quantity));
        return true;
    }

    public void restock(String sku, int quantity) {
        item(sku);
        items.computeIfPresent(sku, (k, it) -> new Item(it.name(), it.price(), it.stock() + quantity));
        orders.onRestock(new StockReplenished(sku));
    }

    private Item item(String sku) {
        Item it = items.get(sku);
        if (it == null) throw new IllegalArgumentException("unknown sku " + sku);
        return it;
    }
}
