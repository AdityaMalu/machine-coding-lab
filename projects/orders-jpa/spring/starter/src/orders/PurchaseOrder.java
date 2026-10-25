package orders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;

/**
 * An order and its lines. The aggregate root: lines are saved and deleted through it.
 *
 * TODO (mapping): the app starts with this, but the defaults are wrong for us. See the README:
 *   - customer: loaded lazily
 *   - status: stored as its name ("PLACED"), not its position
 *   - lines: saved with the order, and a line removed from the list is deleted
 *   - version: optimistic locking
 */
@Entity
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Customer customer;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PLACED;

    @OneToMany(mappedBy = "order")
    @OrderBy("id")
    private List<OrderLine> lines = new ArrayList<>();

    private long version;

    protected PurchaseOrder() { // for JPA
    }

    public PurchaseOrder(Customer customer, Instant createdAt) {
        this.customer = customer;
        this.createdAt = createdAt;
    }

    /** Adds a line, copying the product's current price (later price changes don't touch this order). */
    public OrderLine addLine(Product product, int quantity) {
        if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
        OrderLine line = new OrderLine(this, product, quantity, product.getPriceCents());
        lines.add(line);
        return line;
    }

    public Optional<OrderLine> lineFor(String sku) {
        return lines.stream().filter(l -> l.getProduct().getSku().equals(sku)).findFirst();
    }

    public void removeLine(OrderLine line) {
        lines.remove(line);
    }

    public void cancel() {
        if (status != OrderStatus.PLACED) throw new IllegalStateException("order " + id + " is " + status);
        status = OrderStatus.CANCELLED;
    }

    public long totalCents() {
        return lines.stream().mapToLong(l -> l.getQuantity() * l.getUnitPriceCents()).sum();
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public long getVersion() {
        return version;
    }
}
