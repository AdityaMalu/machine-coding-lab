package catalog;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Today's rates from USD. Not part of the bug hunt. */
@Component
public class ExchangeRates {

    private static final Map<String, BigDecimal> FROM_USD = Map.of(
            "USD", BigDecimal.ONE,
            "EUR", new BigDecimal("0.90"),
            "INR", new BigDecimal("83.00"));

    public BigDecimal fromUsd(String currency) {
        BigDecimal rate = FROM_USD.get(currency);
        if (rate == null) throw new IllegalArgumentException("Unsupported currency " + currency);
        return rate;
    }
}
