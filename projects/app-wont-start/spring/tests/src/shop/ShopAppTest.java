package shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The whole app, default profile. Every test here fails with "Failed to load ApplicationContext" until the app
 * starts; read the "caused by" part of the message. All tests share one app, so each uses its own skus.
 */
@SpringBootTest
class ShopAppTest {

    @Autowired
    ApplicationContext context;
    @Autowired
    OrderService orders;
    @Autowired
    InventoryService inventory;

    String newSku(int stock) {
        String sku = "sku-" + UUID.randomUUID().toString().substring(0, 8);
        inventory.addItem(sku, "Test item", new BigDecimal("30.00"), stock);
        return sku;
    }

    @Test
    @DisplayName("The app starts and binds shop.* from application.properties")
    void bindsProperties() {
        ShopProperties p = context.getBean(ShopProperties.class);
        assertThat(p.currency()).isEqualTo("INR");
        assertThat(p.maxItemsPerOrder()).isEqualTo(20);
        assertThat(p.taxRate()).isEqualByComparingTo("0.18");
    }

    @Test
    @DisplayName("Outside the dev profile, payments go through CardGateway")
    void usesCardGateway() {
        Order o = orders.placeOrder(newSku(5), 1);
        assertThat(o.status()).isEqualTo(OrderStatus.PAID);
        assertThat(o.paymentRef()).isEqualTo("card-" + o.id());
        assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(CardGateway.class);
    }

    @Test
    @DisplayName("Totals include 18% tax: 2 x 30.00 = 70.80")
    void totalsIncludeTax() {
        Order o = orders.placeOrder(newSku(5), 2);
        assertThat(o.total()).isEqualByComparingTo("70.80");
    }

    @Test
    @DisplayName("The receipt shows the total, currency and tax")
    void printsReceipt() {
        String sku = newSku(5);
        Order o = orders.placeOrder(sku, 2);
        assertThat(context.getBean(ReceiptPrinter.class).print(o, new BigDecimal("30.00")))
                .isEqualTo(o.id() + ": 2 x " + sku + " = 70.80 INR (tax 10.80)");
    }

    @Test
    @DisplayName("Quantity must be between 1 and shop.max-items-per-order")
    void quantityLimits() {
        String sku = newSku(50);
        assertThatThrownBy(() -> orders.placeOrder(sku, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orders.placeOrder(sku, 21)).isInstanceOf(IllegalArgumentException.class);
        assertThat(orders.placeOrder(sku, 20).status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("There is one TaxCalculator, shared by PriceQuoter and ReceiptPrinter")
    void oneTaxCalculator() {
        TaxCalculator bean = context.getBean(TaxCalculator.class);
        assertThat(context.getBeansOfType(TaxCalculator.class)).hasSize(1);
        assertThat(context.getBean(PriceQuoter.class).taxCalculator()).as("PriceQuoter's calculator").isSameAs(bean);
        assertThat(context.getBean(ReceiptPrinter.class).taxCalculator()).as("ReceiptPrinter's calculator").isSameAs(bean);
    }

    @Test
    @DisplayName("Out of stock: the order is backordered, and a restock fills waiting orders oldest first")
    void backordersFilledOnRestock() {
        String sku = newSku(0);
        Order first = orders.placeOrder(sku, 1);
        Order second = orders.placeOrder(sku, 1);
        assertThat(first.status()).isEqualTo(OrderStatus.BACKORDERED);
        assertThat(first.paymentRef()).isNull();
        inventory.restock(sku, 1);
        assertThat(orders.get(first.id()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(orders.get(first.id()).paymentRef()).isEqualTo("card-" + first.id());
        assertThat(orders.get(second.id()).status()).isEqualTo(OrderStatus.BACKORDERED);
        inventory.restock(sku, 3);
        assertThat(orders.get(second.id()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(inventory.stock(sku)).isEqualTo(2);
        assertThat(orders.auditOf(first.id())).containsExactly("placed 1 x " + sku, "backordered", "paid after restock");
    }

    @Test
    @DisplayName("InventoryService doesn't depend on OrderService (no cycle)")
    void noCycle() {
        for (Constructor<?> c : InventoryService.class.getDeclaredConstructors()) {
            assertThat(c.getParameterTypes()).as("InventoryService constructor").doesNotContain(OrderService.class);
        }
        for (Field f : InventoryService.class.getDeclaredFields()) {
            assertThat(f.getType()).as("InventoryService." + f.getName()).isNotEqualTo(OrderService.class);
        }
    }

    @Test
    @DisplayName("Every order gets its own audit")
    void ownAudit() {
        String sku = newSku(5);
        Order a = orders.placeOrder(sku, 1);
        Order b = orders.placeOrder(sku, 2);
        assertThat(a.auditId()).isNotEqualTo(b.auditId());
        assertThat(orders.auditOf(a.id())).containsExactly("placed 1 x " + sku, "paid");
        assertThat(orders.auditOf(b.id())).containsExactly("placed 2 x " + sku, "paid");
    }

    @Test
    @DisplayName("30 threads reserving 1 of 10 in stock: exactly 10 succeed, stock ends at 0 (40 rounds)")
    void reserveIsAtomic() throws Exception {
        for (int round = 1; round <= 40; round++) {
            String sku = newSku(10);
            List<Boolean> results = race(30, () -> inventory.reserve(sku, 1));
            assertThat(results.stream().filter(r -> r).count()).as("successful reservations in round " + round).isEqualTo(10);
            assertThat(inventory.stock(sku)).as("stock after round " + round).isZero();
        }
    }

    @Test
    @DisplayName("30 concurrent orders for 10 in stock: 10 paid, 20 backordered, 30 different audits")
    void concurrentOrders() throws Exception {
        String sku = newSku(10);
        List<Order> placed = race(30, () -> orders.placeOrder(sku, 1));
        assertThat(placed.stream().filter(o -> o.status() == OrderStatus.PAID).count()).as("paid").isEqualTo(10);
        assertThat(placed.stream().filter(o -> o.status() == OrderStatus.BACKORDERED).count()).as("backordered").isEqualTo(20);
        Set<String> audits = new HashSet<>();
        for (Order o : placed) audits.add(o.auditId());
        assertThat(audits).as("distinct audit ids").hasSize(30);
        Set<String> ids = new HashSet<>();
        for (Order o : placed) ids.add(o.id());
        assertThat(ids).as("distinct order ids").hasSize(30);
    }

    /** Runs the task on n threads that all start at the same moment. */
    static <T> List<T> race(int n, Callable<T> task) throws Exception {
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
