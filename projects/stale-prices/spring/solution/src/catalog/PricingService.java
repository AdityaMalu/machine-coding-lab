package catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class PricingService {

    /** Orders of this many units or more get the bulk discount. */
    static final int BULK_QUANTITY = 10;
    static final BigDecimal BULK_DISCOUNT = new BigDecimal("0.10");

    private final ProductRepository products;
    private final ExchangeRates rates;

    public PricingService(ProductRepository products, ExchangeRates rates) {
        this.products = products;
        this.rates = rates;
    }

    /**
     * The product in the given currency. Cached per sku AND currency; sync = true means a burst of requests for
     * a product that isn't cached yet loads it once, with the others waiting for that result.
     */
    @Cacheable(cacheNames = "products", key = "#sku + ':' + #currency", sync = true)
    public ProductView view(String sku, String currency) {
        BigDecimal rate = rates.fromUsd(currency);
        Product p = products.find(sku).orElseThrow(() -> new UnknownProductException(sku));
        return new ProductView(p.sku(), p.name(), p.category(), currency, p.priceUsd().multiply(rate).setScale(2, RoundingMode.HALF_UP));
    }

    public Quote quote(ProductView view, int quantity) {
        if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
        BigDecimal gross = view.price().multiply(BigDecimal.valueOf(quantity));
        BigDecimal discount = quantity >= BULK_QUANTITY
                ? gross.multiply(BULK_DISCOUNT).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        return new Quote(view.sku(), view.currency(), quantity, view.price(), discount, gross.subtract(discount).setScale(2, RoundingMode.HALF_UP));
    }

    /** New USD price. Every cached view of the product (in any currency) must go. */
    @CacheEvict(cacheNames = "products", allEntries = true)
    public Product updatePrice(String sku, BigDecimal priceUsd) {
        if (priceUsd.signum() <= 0) throw new IllegalArgumentException("price must be positive");
        Product p = products.find(sku).orElseThrow(() -> new UnknownProductException(sku));
        Product updated = new Product(p.sku(), p.name(), p.category(), priceUsd.setScale(2, RoundingMode.HALF_UP));
        products.save(updated);
        return updated;
    }
}
