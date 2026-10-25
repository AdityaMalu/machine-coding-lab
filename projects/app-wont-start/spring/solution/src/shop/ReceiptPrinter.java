package shop;

import java.math.BigDecimal;

public class ReceiptPrinter {
    private final TaxCalculator taxCalculator;
    private final String currency;

    public ReceiptPrinter(TaxCalculator taxCalculator, String currency) {
        this.taxCalculator = taxCalculator;
        this.currency = currency;
    }

    /** e.g. "ord-1: 2 x apple = 70.80 INR (tax 10.80)" */
    public String print(Order order, BigDecimal unitPrice) {
        BigDecimal net = unitPrice.multiply(BigDecimal.valueOf(order.quantity()));
        return order.id() + ": " + order.quantity() + " x " + order.sku() + " = " + order.total() + " " + currency
                + " (tax " + taxCalculator.tax(net) + ")";
    }

    public TaxCalculator taxCalculator() {
        return taxCalculator;
    }
}
