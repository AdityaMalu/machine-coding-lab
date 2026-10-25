package orders;

public class UnknownProductException extends RuntimeException {
    public UnknownProductException(String sku) {
        super("No product with sku " + sku);
    }
}
