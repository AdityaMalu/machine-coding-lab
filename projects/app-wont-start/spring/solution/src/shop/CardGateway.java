package shop;

import java.math.BigDecimal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The real card processor (simulated). Everywhere except the dev profile. */
@Component
@Profile("!dev")
public class CardGateway implements PaymentGateway {
    @Override
    public String charge(String orderId, BigDecimal amount) {
        return "card-" + orderId;
    }
}
