package catalog;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
public class CatalogController {

    private final PricingService pricing;
    private final ListingService listing;

    public CatalogController(PricingService pricing, ListingService listing) {
        this.pricing = pricing;
        this.listing = listing;
    }

    @GetMapping("/{sku}")
    public ProductView view(@PathVariable String sku, @RequestParam(defaultValue = "USD") String currency) {
        return pricing.view(sku, currency);
    }

    @GetMapping("/{sku}/quote")
    public Quote quote(@PathVariable String sku, @RequestParam(defaultValue = "USD") String currency, @RequestParam int qty) {
        return pricing.quote(pricing.view(sku, currency), qty);
    }

    @GetMapping
    public ProductPage list(@RequestParam(required = false) String category,
                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return listing.list(category, page, size);
    }

    @PutMapping("/{sku}/price")
    public Product updatePrice(@PathVariable String sku, @Valid @RequestBody PriceUpdate body) {
        return pricing.updatePrice(sku, body.priceUsd());
    }
}
