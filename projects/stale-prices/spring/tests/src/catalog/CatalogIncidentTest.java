package catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The incident's acceptance tests. One app (and one cache) is shared, so every test adds its own products. */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogIncidentTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ProductRepository repo;
    @Autowired
    PricingService pricing;
    @Autowired
    ListingService listing;

    static String uid(String p) {
        return p + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    String product(String category, String usd) {
        String sku = uid("SKU");
        repo.save(new Product(sku, "Item " + sku, category, new BigDecimal(usd)));
        return sku;
    }

    @Test
    @DisplayName("A product's page shows its USD price")
    void viewUsd() throws Exception {
        String sku = product("Coffee", "24.00");
        mvc.perform(get("/products/" + sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.price").value(24.00));
    }

    @Test
    @DisplayName("The same product in EUR and INR is converted, not the cached USD price")
    void currencies() throws Exception {
        String sku = product("Coffee", "10.00");
        mvc.perform(get("/products/" + sku)).andExpect(jsonPath("$.price").value(10.00));
        mvc.perform(get("/products/" + sku).param("currency", "EUR"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.price").value(9.00));
        mvc.perform(get("/products/" + sku).param("currency", "INR"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.price").value(830.00));
    }

    @Test
    @DisplayName("Repeated views come from the cache: one database lookup per product and currency")
    void cached() {
        String sku = product("Tea", "5.00");
        int before = repo.lookups();
        for (int i = 0; i < 5; i++) pricing.view(sku, "USD");
        for (int i = 0; i < 5; i++) pricing.view(sku, "EUR");
        assertThat(repo.lookups() - before).as("database lookups").isEqualTo(2);
    }

    @Test
    @DisplayName("After a price change every currency shows the new price at once")
    void priceChange() throws Exception {
        String sku = product("Gear", "100.00");
        mvc.perform(get("/products/" + sku)).andExpect(jsonPath("$.price").value(100.00));
        mvc.perform(get("/products/" + sku).param("currency", "EUR")).andExpect(jsonPath("$.price").value(90.00));
        mvc.perform(put("/products/" + sku + "/price").contentType(MediaType.APPLICATION_JSON).content("{\"priceUsd\": 80.00}"))
                .andExpect(status().isOk());
        mvc.perform(get("/products/" + sku)).andExpect(jsonPath("$.price").value(80.00));
        mvc.perform(get("/products/" + sku).param("currency", "EUR")).andExpect(jsonPath("$.price").value(72.00));
    }

    @Test
    @DisplayName("Bulk discount: 10% off from 10 units (9 units pay full price)")
    void bulkDiscount() throws Exception {
        String sku = product("Coffee", "3.00");
        mvc.perform(get("/products/" + sku + "/quote").param("qty", "9"))
                .andExpect(jsonPath("$.discount").value(0.00))
                .andExpect(jsonPath("$.total").value(27.00));
        mvc.perform(get("/products/" + sku + "/quote").param("qty", "10"))
                .andExpect(jsonPath("$.discount").value(3.00))
                .andExpect(jsonPath("$.total").value(27.00));
        mvc.perform(get("/products/" + sku + "/quote").param("qty", "20").param("currency", "EUR"))
                .andExpect(jsonPath("$.unitPrice").value(2.70))
                .andExpect(jsonPath("$.total").value(48.60));
    }

    @Test
    @DisplayName("Paging a category shows every product exactly once (page is 0-based)")
    void paging() {
        String cat = uid("Cat");
        Set<String> all = new HashSet<>();
        for (int i = 0; i < 7; i++) all.add(product(cat, "1.00"));
        Set<String> seen = new HashSet<>();
        int listed = 0;
        for (int page = 0; page < 3; page++) {
            ProductPage p = listing.list(cat, page, 3);
            assertThat(p.total()).isEqualTo(7);
            for (Product prod : p.items()) {
                seen.add(prod.sku());
                listed++;
            }
        }
        assertThat(listed).as("products listed over 3 pages").isEqualTo(7);
        assertThat(seen).isEqualTo(all);
        assertThat(listing.list(cat, 0, 3).items()).hasSize(3);
        assertThat(listing.list(cat, 2, 3).items()).hasSize(1);
    }

    @Test
    @DisplayName("The category filter ignores case: 'coffee' finds 'Coffee'")
    void categoryCase() throws Exception {
        String cat = uid("Herbal");
        product(cat, "2.00");
        product(cat, "3.00");
        mvc.perform(get("/products").param("category", cat.toLowerCase()))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items", hasSize(2)));
        mvc.perform(get("/products").param("category", cat.toUpperCase()))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("Unknown product: 404. Unknown currency or quantity 0: 400")
    void errors() throws Exception {
        String sku = product("Tea", "4.00");
        mvc.perform(get("/products/NOPE-" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/products/" + sku).param("currency", "XYZ")).andExpect(status().isBadRequest());
        mvc.perform(get("/products/" + sku + "/quote").param("qty", "0")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("30 shoppers open a product nobody has viewed yet: the database is asked once")
    void stampede() throws Exception {
        String sku = product("Gear", "50.00");
        int before = repo.lookups();
        List<ProductView> views = race(30, () -> pricing.view(sku, "USD"));
        assertThat(views).allSatisfy(v -> assertThat(v.price()).isEqualByComparingTo("50.00"));
        assertThat(repo.lookups() - before).as("database lookups for one cold product").isEqualTo(1);
    }

    @Test
    @DisplayName("A price update while shoppers browse: afterwards everyone sees the new price")
    void updateUnderLoad() throws Exception {
        String sku = product("Coffee", "12.00");
        pricing.view(sku, "USD");
        pricing.view(sku, "INR");
        race(10, () -> pricing.view(sku, "USD"));
        pricing.updatePrice(sku, new BigDecimal("15.00"));
        List<ProductView> after = race(10, () -> pricing.view(sku, "USD"));
        assertThat(after).allSatisfy(v -> assertThat(v.price()).isEqualByComparingTo("15.00"));
        assertThat(pricing.view(sku, "INR").price()).isEqualByComparingTo("1245.00");
    }

    static <T> List<T> race(int n, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
