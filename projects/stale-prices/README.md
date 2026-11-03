# Stale Prices Incident (Spring Boot)

**INC-5203 · Sev 2 · Catalog service**

> **Reported by:** Merchandising, 11:05
> We cut the grinder from 189.00 to 149.00 an hour ago; the site still shows 189.00. Customers in Germany
> see USD prices labelled as if they were euros. The "10% off 10 or more" promo isn't applying for exactly 10.
> Also: page 2 of "Coffee" skips products, and the mobile app (which sends `category=coffee`) shows nothing.
> And every morning at 9:00 the database alarms fire when the newsletter link sends everyone to the same
> product.

```
11:02:17 PUT /products/GR-GRN-1/price {"priceUsd":149.00} -> 200
11:02:20 GET /products/GR-GRN-1 -> 200 {"price":189.00,"currency":"USD"}
11:04:41 GET /products/CF-ESP-1?currency=EUR -> 200 {"currency":"USD","price":24.00}
09:00:03 db-pool WARN 412 identical queries for sku=CF-FIL-1 in 1.2 s
```

You're on call. Package `catalog`. `ProductRepository` (a slow database) and `ExchangeRates` are not part of
the bug hunt. Read the code, run the tests, fix the service.

## How it must behave
- `GET /products/{sku}?currency=USD|EUR|INR` (default USD): the product priced in that currency, rounded to
  cents. Unknown sku **404**; unsupported currency **400**.
- Views are **cached**, per product *and* currency: repeated views must not hit the database again. When many
  shoppers open a product that isn't cached yet, the database is asked **once**.
- `PUT /products/{sku}/price`: after a price change, **every currency** shows the new price immediately.
- `GET /products/{sku}/quote?qty=N&currency=…`: 10% bulk discount from **10 units** up. `qty` below 1 is 400.
- `GET /products?category=&page=&size=`: products in the category (case doesn't matter), by sku. `page` is
  0-based, and paging through a category shows every product exactly once.

## Rules of the round
- Don't change the tests, `ProductRepository` or `ExchangeRates`, and don't remove the caching: the database
  can't take the load without it.
- The **✦ Assistant** tab can explain Spring's cache annotations, proxies and the code, but won't point at bugs.

## Follow-ups to think about
- With a Redis cache and several instances, how do you evict on a price change? What about in-flight reads?
- When is `allEntries = true` too blunt?
