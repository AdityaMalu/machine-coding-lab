A build task in two layers: the domain (`IssueService`) enforces the rules, and the HTTP layer maps them onto status codes and headers.

**Domain**
- **Idempotent create.** Keys are scoped per user (`user + key`). The first request stores the trimmed title and body with the new issue's id. A retry with the same payload returns the *original* issue with `replayed = true`; the same key with a different payload is **409**. `create` is `synchronized`, so N concurrent identical retries create exactly one issue.
- **Optimistic concurrency.** Each issue has a `version`. `update` succeeds only if the caller's `expectedVersion` matches, then bumps it: an editor working from an old copy gets **412** instead of silently overwriting someone else's change.
- **Cursor paging that survives inserts.** Issues live in a `TreeMap` by id. The cursor encodes "after id N" (Base64, so it's opaque to clients), and the next page is `tailMap(N, exclusive)`. New issues get higher ids, so they can't shift or duplicate items on pages already read, which offset paging (`skip 40`) can't guarantee. A cursor that doesn't decode is **400**; `nextCursor` is null when nothing is left.
- **Auth and roles.** Unknown token → **401**, readers trying to write → **403**. Validation (title 1..200 after trimming, body ≤ 5000) → **400**.

**HTTP**
- Routes: `POST /issues`, `GET /issues`, `GET /issues/{id}`, `PATCH /issues/{id}`; anything else is 404 or 405.
- `POST` reads the `Idempotency-Key` header and returns **201** with `Location`, or **200** with `Idempotent-Replay: true` for a replay.
- `GET` returns an `ETag` holding the version; `PATCH` *requires* `If-Match` (**428** without it) and passes it on as `expectedVersion`. This is standard HTTP optimistic concurrency.
- `ApiException`s become their status code with a JSON error (plus `WWW-Authenticate` on 401); malformed JSON is 400, and anything unexpected is a 500 that doesn't leak internals.
