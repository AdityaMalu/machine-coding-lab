package shop;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Pricing beans. proxyBeanMethods = false makes startup a little faster. */
@Configuration(proxyBeanMethods = false)
public class PricingConfig {

    @Bean
    TaxCalculator taxCalculator(ShopProperties properties) {
        return new TaxCalculator(properties.taxRate());
    }

    @Bean
    PriceQuoter priceQuoter(ShopProperties properties) {
        return new PriceQuoter(taxCalculator(properties));
    }

    @Bean
    ReceiptPrinter receiptPrinter(ShopProperties properties) {
        return new ReceiptPrinter(taxCalculator(properties), properties.currency());
    }
}
