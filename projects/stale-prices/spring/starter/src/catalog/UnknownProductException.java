package catalog;

public class UnknownProductException extends RuntimeException {
    public UnknownProductException(String sku) {
        super("No product " + sku);
    }
}
