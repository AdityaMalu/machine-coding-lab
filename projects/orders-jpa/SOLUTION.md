A build task about JPA mappings, queries, transactions and locking. Each change answers one README requirement.

**Mappings**
- `@ManyToOne(fetch = LAZY)` everywhere: `ManyToOne` is *eager* by default, which loads customers and products you never asked for.
- `@Enumerated(EnumType.STRING)`: the default stores the enum's position, which silently breaks if someone reorders the constants.
- `cascade = ALL, orphanRemoval = true` on `lines`: the order is the aggregate root, so lines are saved with it, and removing a line from the list deletes its row.
- `@Version`: optimistic locking, so a stale update fails instead of overwriting someone else's change.

**Queries**
- Derived queries: removing the placeholder `default` bodies lets Spring Data write `findByCustomerEmail` (plus the count query a `Page` needs) and `countByCustomerEmailAndStatus` from the method names.
- `@EntityGraph(customer, lines, lines.product)` loads the whole order in one select, which keeps the N+1 budget test happy.
- `totalsFor` and `summariesFor` are JPQL *constructor expressions*: the database groups and sums and returns DTOs directly, so there are no entities and no lazy loading. `left join` + `coalesce` keeps orders that have no lines.

**Transactions and concurrency**
- Every service method is `@Transactional` (`readOnly` for reads). In `placeOrder`, an unknown sku throws an unchecked exception *after* the order was saved, so the whole insert rolls back: all or nothing.
- `addItem` loads the order with `@Lock(PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`): concurrent additions to the same order queue up on the row lock instead of losing quantities.
- Changes to managed entities (`cancel`, `addQuantity`) are written at commit with no explicit `save`.
