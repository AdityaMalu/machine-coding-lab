package shop;

import java.math.BigDecimal;

public class PriceQuoter {
    private final TaxCalculator taxCalculator;

    public PriceQuoter(TaxCalculator taxCalculator) {
        this.taxCalculator = taxCalculator;
    }

    /** Net amount plus tax, in cents precision. */
    public BigDecimal total(BigDecimal unitPrice, int quantity) {
        BigDecimal net = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return net.add(taxCalculator.tax(net)).setScale(2);
    }

    public TaxCalculator taxCalculator() {
        return taxCalculator;
    }
}
