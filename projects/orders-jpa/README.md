# Orders with JPA (Spring Boot)

**Scenario.** The shop is moving its orders from spreadsheets into a database. Build the persistence
layer with Spring Data JPA and Hibernate: map the entities so the database holds the right thing, write
the queries the order pages need, and keep the order list fast and correct under concurrent updates.

The database is an in-memory H2 that Hibernate creates from your mappings when the app starts. Plain-SQL
checks in the tests read the tables directly, so the mapping itself is part of the spec.

## The model

Package `orders`. `Customer` and `Product` are finished; use them as examples.

```
Customer 1 ── * PurchaseOrder 1 ── * OrderLine * ── 1 Product
```

- `PurchaseOrder` (table `purchase_order`): customer, `createdAt`, `status` (`PLACED` / `CANCELLED`), lines, version.
- `OrderLine` (table `order_line`): order, product, `quantity`, `unitPriceCents` copied from the product when
  the line is added, so a later price change doesn't change old orders.
- `CustomerTotal(email, totalCents)` and `OrderSummary(id, status, customerName, itemCount, totalCents)` are
  plain records for query results.

## What to build

### 1. Mappings (`PurchaseOrder`, `OrderLine`)
- Every `@ManyToOne` is **lazy**. `findById` must not load the customer.
- `status` is stored as **text** (`PLACED`), not as a number.
- Saving an order saves its lines; removing a line from `getLines()` **deletes** its row.
- `version` turns on **optimistic locking**: saving a stale copy of an order fails.

### 2. Queries (`PurchaseOrderRepository`)
| Method | How |
|---|---|
| `findByCustomerEmail(email, pageable)` | derived query, returns a `Page` (sorting comes from the `Pageable`) |
| `countByCustomerEmailAndStatus(email, status)` | derived query |
| `findWithLinesById(id)` | `@EntityGraph`: customer, lines and their products in **one** SQL statement |
| `findForUpdate(id)` | `@Lock(PESSIMISTIC_WRITE)` + `@Query`: `SELECT ... FOR UPDATE` |
| `totalsFor(emails)` | `@Query` with `select new orders.CustomerTotal(...)`, `group by`; cancelled orders excluded; biggest total first |
| `summariesFor(email)` | `@Query` returning `OrderSummary` rows, ordered by order id, computed by the database |

### 3. Service (`OrderService`)
- `placeOrder(email, items)`: unknown customer, no items or a quantity under 1 → `IllegalArgumentException`;
  unknown sku → `UnknownProductException`. **All or nothing**: a failure leaves no order behind.
- `cancel(id)`: `PLACED` → `CANCELLED`; anything else `IllegalStateException`; unknown id `OrderNotFoundException`.
- `removeLine(id, sku)`.
- `addItem(id, sku, qty)`: adds to the sku's line or adds a line. **10 requests at once must all count.**
- `summaries(email)`: at most **2 SQL statements** however many orders there are (the test counts them).

## Hints
- `@ManyToOne(fetch = FetchType.LAZY)`, `@Enumerated(EnumType.STRING)`,
  `@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)`, `@Version`.
- `@Transactional` on the service methods. A runtime exception rolls the transaction back; a checked one doesn't
  unless you say so.
- Default methods in a repository interface are plain Java. Remove `default` and the body to get a derived
  query; Spring Data checks the method names when the app starts and tells you if one is wrong.
- N+1: loading 10 orders and then touching `order.getLines()` runs 1 + 10 queries. Let the database add up
  the totals (a JPQL constructor expression with `sum` and `group by`), or fetch everything in one go.
- `@Version` alone makes concurrent `addItem` calls **fail** instead of losing updates. Either retry, or
  lock the row first so they wait their turn.

## Follow-ups to think about
- Optimistic vs pessimistic locking: which one would you pick for a busy checkout, and why?
- Why does Spring Boot warn about `spring.jpa.open-in-view`, and why is it off here?
- `@EntityGraph` with two collections at once causes a `MultipleBagFetchException` (or a cartesian
  product). How would you load an order with lines *and* payments?
