# App Won't Start (Spring Boot)

**Scenario.** Monday morning. Over the weekend someone "cleaned up" the corner-shop backend: they moved
config around, added a fake payment gateway for local testing, and taught the inventory to fill waiting
orders after a restock. Now the app won't even start. Once it does, support says customers share order
histories and the shop sometimes sells more apples than it has.

You're on call. Get the app starting, then make it correct. This is a **debug** project: the code is
nearly right, and the tests are the spec.

## How the shop should work

Package `shop`. Read the code first: `OrderService` places orders, `InventoryService` holds stock, the
gateways take payments, `PricingConfig` builds the pricing beans.

- **Config** comes from `shop.*` in `application.properties` into the `ShopProperties` record:
  `currency` (3 upper-case letters, `INR`), `max-items-per-order` (1..100, `20`), `tax-rate` (0..0.5, `0.18`).
  A bad or missing value must **stop the app at startup**, not fail later.
- **Payments:** `CardGateway` everywhere, except the `dev` profile, where `FakeGateway` takes over.
  There must always be exactly one gateway to inject.
- **Pricing:** one `TaxCalculator` bean, shared by `PriceQuoter` and `ReceiptPrinter`.
  2 x 30.00 = 60.00 + 18% tax = **70.80**.
- **Orders:** quantity 1..`max-items-per-order`. In stock: `PAID` with a payment reference. Out of stock:
  `BACKORDERED`, and a later restock fills waiting orders for that sku, oldest first.
- **`InventoryService` must not depend on `OrderService`.** A restock announces itself with a
  `StockReplenished` application event, and `OrderService` listens with `@EventListener`.
- **Every order has its own `OrderAudit`** (a prototype-scoped bean): its own id and only its own events.
- **Thread-safe:** 30 threads buying the last 10 apples get exactly 10 sales, never 11.

## How to work
1. Run the tests. While the app can't start, every `ShopAppTest` fails with **The app failed to start**.
   Read the cause on the first failure, and the **APPLICATION FAILED TO START** report in the output: Spring
   Boot says what's wrong and often how to fix it.
2. Fix one startup problem, run again, repeat. There are several, and Spring reports one at a time.
3. When the app starts, fix the behaviour bugs the remaining tests point at.
4. `WiringTest` builds small contexts from a few classes, so those tests can pass before the whole app does.

## Hints
- Two beans of one type: `@Primary`, `@Qualifier`, or `@Profile` (`@Profile("!dev")` means "not dev").
- A `@ConfigurationProperties` record is only a bean if something registers it:
  `@ConfigurationPropertiesScan` or `@EnableConfigurationProperties`. Keys use kebab-case and must match the
  record's components (`maxItemsPerOrder` binds from `max-items-per-order`).
- `@Configuration(proxyBeanMethods = false)` ("lite mode") means calling one `@Bean` method from another
  just runs it again, creating a second object. Take the bean as a method parameter instead.
- A prototype bean injected into a singleton is created **once**. Ask for a new one each time:
  `ObjectProvider<OrderAudit>` and `getObject()`.
- Read-then-write on a `ConcurrentHashMap` is still a race. `compute` / `computeIfPresent` make it one step.

## Follow-ups to think about
- Why does Spring Boot forbid circular references by default since 2.6? When is `@Lazy` an honest fix?
- Events here are synchronous and run in the publisher's thread and transaction. What changes with
  `@Async` or `@TransactionalEventListener`?
- What would you log or expose (actuator, `/actuator/conditions`) to debug startup in production?
