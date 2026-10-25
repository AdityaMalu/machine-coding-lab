package shop;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final InventoryService inventory;
    private final PaymentGateway payments;
    private final PriceQuoter quoter;
    private final ShopProperties properties;
    private final OrderAudit audit;

    private final AtomicLong ids = new AtomicLong();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, OrderAudit> auditByOrder = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> backorders = new ConcurrentHashMap<>();

    public OrderService(InventoryService inventory, PaymentGateway payments, PriceQuoter quoter,
                        ShopProperties properties, OrderAudit audit) {
        this.inventory = inventory;
        this.payments = payments;
        this.quoter = quoter;
        this.properties = properties;
        this.audit = audit;
    }

    public Order placeOrder(String sku, int quantity) {
        if (quantity < 1 || quantity > properties.maxItemsPerOrder()) {
            throw new IllegalArgumentException("quantity must be 1.." + properties.maxItemsPerOrder());
        }
        BigDecimal total = quoter.total(inventory.price(sku), quantity);
        String id = "ord-" + ids.incrementAndGet();
        audit.record("placed " + quantity + " x " + sku);
        auditByOrder.put(id, audit);
        if (inventory.reserve(sku, quantity)) return paid(id, sku, quantity, total, audit);
        synchronized (this) {
            if (inventory.reserve(sku, quantity)) return paid(id, sku, quantity, total, audit);
            Order order = new Order(id, sku, quantity, OrderStatus.BACKORDERED, total, null, audit.id());
            audit.record("backordered");
            orders.put(id, order);
            backorders.computeIfAbsent(sku, k -> new ArrayDeque<>()).addLast(id);
            return order;
        }
    }

    private Order paid(String id, String sku, int quantity, BigDecimal total, OrderAudit audit) {
        Order order = new Order(id, sku, quantity, OrderStatus.PAID, total, payments.charge(id, total), audit.id());
        audit.record("paid");
        orders.put(id, order);
        return order;
    }

    public Order get(String id) {
        Order o = orders.get(id);
        if (o == null) throw new IllegalArgumentException("unknown order " + id);
        return o;
    }

    public List<String> auditOf(String orderId) {
        OrderAudit audit = auditByOrder.get(orderId);
        if (audit == null) throw new IllegalArgumentException("unknown order " + orderId);
        return audit.events();
    }

    /** Fills waiting orders for the sku, oldest first, while stock lasts. */
    public synchronized void onRestock(StockReplenished event) {
        Deque<String> waiting = backorders.get(event.sku());
        while (waiting != null && !waiting.isEmpty()) {
            Order o = orders.get(waiting.peekFirst());
            if (!inventory.reserve(o.sku(), o.quantity())) break;
            waiting.removeFirst();
            orders.put(o.id(), new Order(o.id(), o.sku(), o.quantity(), OrderStatus.PAID, o.total(),
                    payments.charge(o.id(), o.total()), o.auditId()));
            auditByOrder.get(o.id()).record("paid after restock");
        }
    }
}
