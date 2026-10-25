package orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The spec for Orders with JPA. One app and one in-memory database are shared by all tests, so every test
 * makes its own customers and products (unique emails and skus) and never assumes empty tables.
 * Tests run outside any transaction, like a controller would: each repository or service call is its own.
 */
@SpringBootTest
class OrdersJpaTest {

    @Autowired
    OrderService service;
    @Autowired
    CustomerRepository customers;
    @Autowired
    ProductRepository products;
    @Autowired
    PurchaseOrderRepository orders;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManagerFactory emf;

    static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    Customer customer() {
        String id = uid();
        return customers.save(new Customer("Customer " + id, id + "@example.com"));
    }

    Product product(long priceCents) {
        return products.save(new Product("sku-" + uid(), "Product", priceCents));
    }

    static Map<String, Integer> items(Object... skuQty) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < skuQty.length; i += 2) m.put((String) skuQty[i], (Integer) skuQty[i + 1]);
        return m;
    }

    Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    int lineRows(long orderId) {
        return jdbc.queryForObject("select count(*) from order_line where order_id = ?", Integer.class, orderId);
    }

    @Test
    @DisplayName("placeOrder saves the order and its lines, with each line's price copied at order time")
    void placesOrder() {
        Customer c = customer();
        Product a = product(250);
        Product b = product(1000);
        long id = service.placeOrder(c.getEmail(), items(a.getSku(), 2, b.getSku(), 1)).getId();
        a.setPriceCents(999);
        products.save(a); // a later price change must not change the order
        PurchaseOrder o = orders.findWithLinesById(id).orElseThrow();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(o.getCreatedAt()).isNotNull();
        assertThat(o.getLines()).hasSize(2);
        assertThat(o.getLines().get(0).getUnitPriceCents()).isEqualTo(250);
        assertThat(o.totalCents()).isEqualTo(2 * 250 + 1000);
        assertThat(lineRows(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("Saving an order saves its lines too (cascade)")
    void cascadesLines() {
        Customer c = customer();
        PurchaseOrder o = new PurchaseOrder(c, java.time.Instant.now());
        o.addLine(product(100), 1);
        o.addLine(product(200), 3);
        long id = orders.save(o).getId();
        assertThat(lineRows(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("An unknown sku fails the whole order: nothing is saved (rollback)")
    void unknownSkuRollsBack() {
        Customer c = customer();
        Product a = product(100);
        assertThatThrownBy(() -> service.placeOrder(c.getEmail(), items(a.getSku(), 1, "no-such-sku", 1)))
                .isInstanceOf(UnknownProductException.class);
        assertThat(orders.countByCustomerEmailAndStatus(c.getEmail(), OrderStatus.PLACED)).isZero();
    }

    @Test
    @DisplayName("No items, a quantity under 1 or an unknown customer: IllegalArgumentException")
    void rejectsBadOrders() {
        Customer c = customer();
        Product a = product(100);
        assertThatThrownBy(() -> service.placeOrder(c.getEmail(), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.placeOrder(c.getEmail(), items(a.getSku(), 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.placeOrder("nobody@example.com", items(a.getSku(), 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(orders.countByCustomerEmailAndStatus(c.getEmail(), OrderStatus.PLACED)).isZero();
    }

    @Test
    @DisplayName("The status column holds the enum's name (PLACED), not its position")
    void statusStoredAsText() {
        long id = service.placeOrder(customer().getEmail(), items(product(100).getSku(), 1)).getId();
        assertThat(jdbc.queryForObject("select status from purchase_order where id = ?", String.class, id))
                .isEqualTo("PLACED");
    }

    @Test
    @DisplayName("cancel: PLACED becomes CANCELLED; cancelling again or an unknown order fails")
    void cancels() {
        long id = service.placeOrder(customer().getEmail(), items(product(100).getSku(), 1)).getId();
        service.cancel(id);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> service.cancel(id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.cancel(987654321L)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("removeLine deletes the line's row (orphan removal)")
    void removesLine() {
        Product a = product(100);
        Product b = product(200);
        long id = service.placeOrder(customer().getEmail(), items(a.getSku(), 1, b.getSku(), 2)).getId();
        service.removeLine(id, a.getSku());
        assertThat(lineRows(id)).isEqualTo(1);
        assertThat(orders.findWithLinesById(id).orElseThrow().totalCents()).isEqualTo(400);
    }

    @Test
    @DisplayName("Two stale copies of one order: the second save fails (@Version optimistic locking)")
    void optimisticLocking() {
        long id = service.placeOrder(customer().getEmail(), items(product(100).getSku(), 1)).getId();
        PurchaseOrder first = orders.findById(id).orElseThrow();
        PurchaseOrder second = orders.findById(id).orElseThrow();
        first.cancel();
        orders.save(first);
        second.cancel();
        assertThatThrownBy(() -> orders.save(second)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(orders.findById(id).orElseThrow().getVersion()).isEqualTo(first.getVersion() + 1);
    }

    @Test
    @DisplayName("An order's customer is loaded lazily")
    void customerIsLazy() {
        long id = service.placeOrder(customer().getEmail(), items(product(100).getSku(), 1)).getId();
        PurchaseOrder o = orders.findById(id).orElseThrow();
        assertThat(Hibernate.isInitialized(o.getCustomer())).as("customer loaded by findById").isFalse();
    }

    @Test
    @DisplayName("findWithLinesById loads customer, lines and products in one SQL statement")
    void entityGraphOneQuery() {
        long id = service.placeOrder(customer().getEmail(), items(product(100).getSku(), 1, product(200).getSku(), 1)).getId();
        stats().clear();
        PurchaseOrder o = orders.findWithLinesById(id).orElseThrow();
        assertThat(stats().getPrepareStatementCount()).as("SQL statements").isEqualTo(1);
        assertThat(Hibernate.isInitialized(o.getCustomer())).isTrue();
        assertThat(Hibernate.isInitialized(o.getLines())).isTrue();
        assertThat(Hibernate.isInitialized(o.getLines().get(0).getProduct())).isTrue();
    }

    @Test
    @DisplayName("findByCustomerEmail pages and sorts: 5 orders, pages of 2, newest id first")
    void pages() {
        Customer c = customer();
        String sku = product(100).getSku();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(service.placeOrder(c.getEmail(), items(sku, 1)).getId());
        Page<PurchaseOrder> page = orders.findByCustomerEmail(c.getEmail(), PageRequest.of(0, 2, Sort.by("id").descending()));
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).extracting(PurchaseOrder::getId).containsExactly(ids.get(4), ids.get(3));
        Page<PurchaseOrder> last = orders.findByCustomerEmail(c.getEmail(), PageRequest.of(2, 2, Sort.by("id").descending()));
        assertThat(last.getContent()).extracting(PurchaseOrder::getId).containsExactly(ids.get(0));
    }

    @Test
    @DisplayName("countByCustomerEmailAndStatus counts per status")
    void countsByStatus() {
        Customer c = customer();
        String sku = product(100).getSku();
        long first = service.placeOrder(c.getEmail(), items(sku, 1)).getId();
        service.placeOrder(c.getEmail(), items(sku, 1));
        service.cancel(first);
        assertThat(orders.countByCustomerEmailAndStatus(c.getEmail(), OrderStatus.PLACED)).isEqualTo(1);
        assertThat(orders.countByCustomerEmailAndStatus(c.getEmail(), OrderStatus.CANCELLED)).isEqualTo(1);
    }

    @Test
    @DisplayName("totalsFor: spend per customer, cancelled orders left out, biggest spender first")
    void totals() {
        Customer small = customer();
        Customer big = customer();
        Product p = product(1000);
        service.placeOrder(small.getEmail(), items(p.getSku(), 1));
        service.placeOrder(big.getEmail(), items(p.getSku(), 2));
        service.placeOrder(big.getEmail(), items(p.getSku(), 3));
        service.cancel(service.placeOrder(small.getEmail(), items(p.getSku(), 50)).getId());
        assertThat(orders.totalsFor(List.of(small.getEmail(), big.getEmail()))).containsExactly(
                new CustomerTotal(big.getEmail(), 5000L), new CustomerTotal(small.getEmail(), 1000L));
    }

    @Test
    @DisplayName("summaries: 10 orders x 3 lines in at most 2 SQL statements (no N+1)")
    void summariesWithoutNPlusOne() {
        Customer c = customer();
        Product a = product(100);
        Product b = product(200);
        Product d = product(300);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) ids.add(service.placeOrder(c.getEmail(), items(a.getSku(), 1, b.getSku(), 2, d.getSku(), i + 1)).getId());
        service.cancel(ids.get(0));
        stats().clear();
        List<OrderSummary> list = service.summaries(c.getEmail());
        assertThat(stats().getPrepareStatementCount()).as("SQL statements for 10 orders").isLessThanOrEqualTo(2);
        assertThat(list).hasSize(10);
        assertThat(list.get(0)).isEqualTo(new OrderSummary(ids.get(0), OrderStatus.CANCELLED, c.getName(), 4L, 100L + 400 + 300));
        assertThat(list.get(9)).isEqualTo(new OrderSummary(ids.get(9), OrderStatus.PLACED, c.getName(), 13L, 100L + 400 + 3000));
    }

    @Test
    @DisplayName("Two customers can't share an email (unique constraint)")
    void uniqueEmail() {
        Customer c = customer();
        assertThatThrownBy(() -> customers.save(new Customer("Copy", c.getEmail())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("10 threads adding 1 to the same line at once: the quantity ends exactly 10 higher")
    void concurrentAddItem() throws Exception {
        Product p = product(100);
        long id = service.placeOrder(customer().getEmail(), items(p.getSku(), 1)).getId();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    service.addItem(id, p.getSku(), 1);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        PurchaseOrder o = orders.findWithLinesById(id).orElseThrow();
        assertThat(o.getLines()).hasSize(1);
        assertThat(o.getLines().get(0).getQuantity()).isEqualTo(11);
    }
}
