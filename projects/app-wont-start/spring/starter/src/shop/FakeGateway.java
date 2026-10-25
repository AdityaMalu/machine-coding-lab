package shop;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

/** Accepts every payment without charging anyone. Only for the dev profile. */
@Component
public class FakeGateway implements PaymentGateway {
    @Override
    public String charge(String orderId, BigDecimal amount) {
        return "fake-" + orderId;
    }
}
