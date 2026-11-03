package payments;

/** An Idempotency-Key was reused for a different request. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String key) {
        super("Idempotency-Key " + key + " was already used for a different request");
    }
}
