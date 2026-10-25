package shop;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The quoter and the printer must share the one TaxCalculator bean. Taking it as a parameter works in any
 * mode; calling taxCalculator() directly only works when Spring proxies this class (proxyBeanMethods = true).
 */
@Configuration(proxyBeanMethods = false)
public class PricingConfig {

    @Bean
    TaxCalculator taxCalculator(ShopProperties properties) {
        return new TaxCalculator(properties.taxRate());
    }

    @Bean
    PriceQuoter priceQuoter(TaxCalculator taxCalculator) {
        return new PriceQuoter(taxCalculator);
    }

    @Bean
    ReceiptPrinter receiptPrinter(TaxCalculator taxCalculator, ShopProperties properties) {
        return new ReceiptPrinter(taxCalculator, properties.currency());
    }
}
