# Notes API (Spring Boot)

**Scenario.** A small team wants a notes service behind their internal tools. Build its REST API with
Spring Boot: controllers, a service layer, request validation and proper error responses, and keep it
correct when many requests arrive at once.

This project is Spring Boot only. The app class, the records and empty controller, service and error
handler classes are already there; the tests are the full spec.

## What to build

Package `notes`. Notes are kept in memory (no database yet; JPA comes in a later project).

```java
public record Note(long id, String title, String body, List<String> tags, Instant createdAt, Instant updatedAt) {}
public record NoteRequest(String title, String body, List<String> tags) {}
public record NotePage(List<Note> items, int page, int size, long total) {}
```

### Endpoints

| Method and path | Success | Errors |
|---|---|---|
| `POST /notes` | **201**, `Location: /notes/{id}`, the new note | 400 invalid, 409 duplicate title |
| `GET /notes/{id}` | 200, the note | 404 |
| `GET /notes?tag=&page=0&size=20` | 200, a `NotePage` | 400 bad page or size |
| `PUT /notes/{id}` | 200, the updated note | 400, 404, 409 |
| `DELETE /notes/{id}` | **204**, no body | 404 |

### Rules
- **Validation** (`@Valid` + Jakarta constraints on `NoteRequest`): `title` is required, not blank, at most
  100 characters. `body` is optional (missing means `""`), at most 5000 characters. `tags` is optional
  (missing means empty), at most 5 tags, and each tag is not blank and at most 20 characters.
- Tags are stored **trimmed and lower-case**, without duplicates, in the order first given.
- Titles are **unique, ignoring case and surrounding spaces**. Store the title trimmed.
- ids start at 1 and are never reused. `createdAt` is set once; `updatedAt` changes on every update.
  Read time only from the injected `Clock` bean.
- Update replaces title, body and tags. Keeping your own title (in any case) is not a conflict.
- Listing is ordered by id ascending, optionally filtered by one tag (matched case-insensitively), then
  paged. `page` ≥ 0, `size` between 1 and 50 (use constraints on the `@RequestParam`s).
- **Errors are `ProblemDetail`** (`application/problem+json`) from a `@RestControllerAdvice`:
  - 404: title `Note not found`
  - 409: title `Duplicate title`
  - 400 from body validation: title `Invalid request`, and an `errors` property mapping each bad field
    (`title`, `tags`, `tags[0]` ...) to a message.
  - 400 from a bad `page` / `size`: title `Invalid request`.
- **Thread-safe.** 50 requests creating the same title at the same moment: exactly one gets 201, the rest 409.
- Use **constructor injection** everywhere (no `@Autowired` fields).

## Hints
- `ResponseEntity.created(uri).body(note)` sets both the status and the `Location` header.
- `ProblemDetail.forStatusAndDetail(...)`, then `setTitle` and `setProperty("errors", map)`.
- Body validation failures throw `MethodArgumentNotValidException`; constraints on `@RequestParam`s throw
  `HandlerMethodValidationException` (Spring 6.1+ validates controller parameters by itself).
- Check-then-insert on the title is a race. Make the check and the insert one atomic step.

## Follow-ups to think about
- Why does `@Valid` on a `@RequestBody` throw a different exception from a constraint on a `@RequestParam`?
- What changes if two instances of this service run behind a load balancer? (The title check moves to a
  unique index in the database.)
- Would you return 400 or 422 for validation errors? What do clients expect?
