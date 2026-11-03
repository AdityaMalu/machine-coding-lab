package catalog;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * The product database (simulated). Each lookup is a slow query, which is why views are cached.
 * It counts lookups so we can see how often the cache misses. Not part of the bug hunt.
 */
@Component
public class ProductRepository {

    private final Map<String, Product> products = new ConcurrentHashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();

    public ProductRepository() {
        save(new Product("CF-ESP-1", "Espresso beans 1 kg", "Coffee", new BigDecimal("24.00")));
        save(new Product("CF-FIL-1", "Filter roast 500 g", "Coffee", new BigDecimal("14.50")));
        save(new Product("TE-SEN-1", "Sencha 100 g", "Tea", new BigDecimal("9.90")));
        save(new Product("GR-GRN-1", "Burr grinder", "Gear", new BigDecimal("189.00")));
    }

    public Optional<Product> find(String sku) {
        lookups.incrementAndGet();
        try {
            Thread.sleep(20); // a database round trip
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.ofNullable(products.get(sku));
    }

    public void save(Product p) {
        products.put(p.sku(), p);
    }

    /** All products, ordered by sku. */
    public List<Product> all() {
        return products.values().stream().sorted(Comparator.comparing(Product::sku)).toList();
    }

    public int lookups() {
        return lookups.get();
    }
}
