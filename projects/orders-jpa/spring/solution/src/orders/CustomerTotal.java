package orders;

/** One row of the spend report: a customer's total over orders that aren't cancelled. */
public record CustomerTotal(String email, Long totalCents) {}
