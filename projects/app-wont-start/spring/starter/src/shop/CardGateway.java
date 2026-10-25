package shop;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

/** The real card processor (simulated). */
@Component
public class CardGateway implements PaymentGateway {
    @Override
    public String charge(String orderId, BigDecimal amount) {
        return "card-" + orderId;
    }
}
