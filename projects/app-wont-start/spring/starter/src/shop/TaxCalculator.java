package shop;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class TaxCalculator {
    private final BigDecimal rate;

    public TaxCalculator(BigDecimal rate) {
        this.rate = rate;
    }

    public BigDecimal tax(BigDecimal amount) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
