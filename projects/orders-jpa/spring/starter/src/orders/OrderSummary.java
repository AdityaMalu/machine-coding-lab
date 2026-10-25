package orders;

/** One row of a customer's order list. */
public record OrderSummary(Long id, OrderStatus status, String customerName, Long itemCount, Long totalCents) {}
