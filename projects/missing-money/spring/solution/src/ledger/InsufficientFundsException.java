package ledger;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(Long accountId) {
        super("Account " + accountId + " has insufficient funds");
    }
}
