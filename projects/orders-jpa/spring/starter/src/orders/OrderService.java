package orders;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/** TODO: implement every method (see the README), each in the right transaction. */
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

    /** A new PLACED order for the customer, one line per sku. All or nothing. */
    public PurchaseOrder placeOrder(String email, Map<String, Integer> items) {
        throw new UnsupportedOperationException("TODO");
    }

    public void cancel(long orderId) {
        throw new UnsupportedOperationException("TODO");
    }

    public void removeLine(long orderId, String sku) {
        throw new UnsupportedOperationException("TODO");
    }

    /** Adds quantity to the sku's line, or adds a line. Safe when many requests do this at once. */
    public void addItem(long orderId, String sku, int quantity) {
        throw new UnsupportedOperationException("TODO");
    }

    public List<OrderSummary> summaries(String email) {
        throw new UnsupportedOperationException("TODO");
    }
}
