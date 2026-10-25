package shop;

import java.math.BigDecimal;

public record Order(String id, String sku, int quantity, OrderStatus status, BigDecimal total,
                    String paymentRef, String auditId) {}
