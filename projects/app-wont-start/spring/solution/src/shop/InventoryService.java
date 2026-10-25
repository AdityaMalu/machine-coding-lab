package shop;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    record Item(String name, BigDecimal price, int stock) {}

    private final Map<String, Item> items = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;

    public InventoryService(ApplicationEventPublisher events) {
        this.events = events;
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

    /** Takes quantity units if that many are in stock, as one atomic step per sku: no overselling. */
    public boolean reserve(String sku, int quantity) {
        item(sku);
        boolean[] taken = {false};
        items.computeIfPresent(sku, (k, it) -> {
            if (it.stock() < quantity) return it;
            taken[0] = true;
            return new Item(it.name(), it.price(), it.stock() - quantity);
        });
        return taken[0];
    }

    public void restock(String sku, int quantity) {
        item(sku);
        items.computeIfPresent(sku, (k, it) -> new Item(it.name(), it.price(), it.stock() + quantity));
        events.publishEvent(new StockReplenished(sku));
    }

    private Item item(String sku) {
        Item it = items.get(sku);
        if (it == null) throw new IllegalArgumentException("unknown sku " + sku);
        return it;
    }
}
