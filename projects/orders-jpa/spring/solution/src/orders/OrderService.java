package orders;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final PurchaseOrderRepository orders;
    private final Clock clock;

    public OrderService(CustomerRepository customers, ProductRepository products, PurchaseOrderRepository orders,
                        Clock clock) {
        this.customers = customers;
        this.products = products;
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * One transaction: the order is saved first, so an unknown sku later on rolls the insert back
     * (UnknownProductException is unchecked, so @Transactional rolls back on it).
     */
    @Transactional
    public PurchaseOrder placeOrder(String email, Map<String, Integer> items) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("an order needs at least one item");
        Customer customer = customers.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("no customer " + email));
        PurchaseOrder order = orders.save(new PurchaseOrder(customer, clock.instant()));
        for (Map.Entry<String, Integer> item : items.entrySet()) {
            Product product = products.findBySku(item.getKey()).orElseThrow(() -> new UnknownProductException(item.getKey()));
            order.addLine(product, item.getValue());
        }
        return order;
    }

    @Transactional
    public void cancel(long orderId) {
        load(orderId).cancel();
    }

    @Transactional
    public void removeLine(long orderId, String sku) {
        PurchaseOrder order = load(orderId);
        order.lineFor(sku).ifPresent(order::removeLine);
    }

    /** Adds to the sku's line (or a new line). The row lock makes concurrent additions queue up, not collide. */
    @Transactional
    public void addItem(long orderId, String sku, int quantity) {
        PurchaseOrder order = orders.findForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.PLACED) throw new IllegalStateException("order " + orderId + " is " + order.getStatus());
        Product product = products.findBySku(sku).orElseThrow(() -> new UnknownProductException(sku));
        order.lineFor(sku).ifPresentOrElse(l -> l.addQuantity(quantity), () -> order.addLine(product, quantity));
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> summaries(String email) {
        return orders.summariesFor(email);
    }

    private PurchaseOrder load(long id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
