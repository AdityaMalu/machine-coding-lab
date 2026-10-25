package shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Small contexts built from just a few classes (ApplicationContextRunner): fast, and each one checks a
 * single piece of wiring, so these can pass before the whole app starts.
 */
class WiringTest {

    @Configuration
    @EnableConfigurationProperties(ShopProperties.class)
    static class PropertiesOnly {
    }

    final ApplicationContextRunner gateways = new ApplicationContextRunner()
            .withUserConfiguration(CardGateway.class, FakeGateway.class);

    final ApplicationContextRunner properties = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    @DisplayName("Default profile: exactly one PaymentGateway to inject, the CardGateway")
    void defaultGateway() {
        gateways.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(PaymentGateway.class)).isInstanceOf(CardGateway.class);
        });
    }

    @Test
    @DisplayName("dev profile: the FakeGateway is the one injected")
    void devGateway() {
        gateways.withPropertyValues("spring.profiles.active=dev").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(PaymentGateway.class)).isInstanceOf(FakeGateway.class);
        });
    }

    @Test
    @DisplayName("Valid shop.* properties bind")
    void validProperties() {
        properties.withPropertyValues("shop.currency=EUR", "shop.max-items-per-order=7", "shop.tax-rate=0.2").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ShopProperties p = ctx.getBean(ShopProperties.class);
            assertThat(p.currency()).isEqualTo("EUR");
            assertThat(p.maxItemsPerOrder()).isEqualTo(7);
        });
    }

    @Test
    @DisplayName("Bad config stops the app at startup: lower-case currency")
    void badCurrencyFails() {
        properties.withPropertyValues("shop.currency=inr", "shop.max-items-per-order=7", "shop.tax-rate=0.2")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("Bad config stops the app at startup: max items missing (a misspelled key), tax rate over 50%")
    void missingOrOutOfRangeFails() {
        properties.withPropertyValues("shop.currency=INR", "shop.max-items=7", "shop.tax-rate=0.2")
                .run(ctx -> assertThat(ctx).hasFailed());
        properties.withPropertyValues("shop.currency=INR", "shop.max-items-per-order=7", "shop.tax-rate=0.9")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
