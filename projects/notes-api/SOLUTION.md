A build task: the solution fills in the controller, validation, error handling and a thread-safe service.

- **Controller.** Standard REST mapping: `POST` returns **201** with a `Location: /notes/{id}` header (`ResponseEntity.created`), and `DELETE` returns **204** (`@ResponseStatus(NO_CONTENT)`). `@Valid` on the bodies triggers bean validation; `@Min`/`@Max` on `page` and `size` reject out-of-range paging values.
- **Validation lives on the record.** `@NotBlank @Size(max = 100)` title, `@Size(max = 5000)` body, and `@Size(max = 5) List<@NotBlank @Size(max = 20) String>` checks the list *and* each tag. Constraints on the type keep the controller free of `if` checks.
- **Errors as `ProblemDetail`.** One `@RestControllerAdvice` maps not found → 404, duplicate title → 409, an invalid body → 400 with an `errors` map (field → message, from the binding result), and invalid query parameters → 400.
- **Race-free unique titles.** A title is unique ignoring case and surrounding spaces, so the lookup key is `strip().toLowerCase(Locale.ROOT)`. "Check the title, then insert" must be one step, or two concurrent requests both pass the check: the write methods are `synchronized`, while reads go straight to `ConcurrentHashMap`s, so a `GET` never waits.
- **Updates rename safely.** Keeping your own title is allowed (`owner != id`), and the old title's key is removed when the title changes.
- **Tags** are trimmed, lower-cased and de-duplicated in order (`LinkedHashSet`). Listing sorts by id so pages are stable.
