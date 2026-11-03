package catalog;

import java.util.List;

import org.springframework.stereotype.Service;

/** Category pages for the shop front. */
@Service
public class ListingService {

    private final ProductRepository products;

    public ListingService(ProductRepository products) {
        this.products = products;
    }

    /** Products in the category, by sku, one page at a time; page is 0-based. */
    public ProductPage list(String category, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("bad page or size");
        List<Product> matching = products.all().stream()
                .filter(p -> category == null || p.category().equals(category))
                .toList();
        int from = Math.min(page * size, matching.size());
        int to = Math.min(from + size - 1, matching.size());
        return new ProductPage(matching.subList(from, to), page, size, matching.size());
    }
}
