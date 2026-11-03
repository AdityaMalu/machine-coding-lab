package reminders;

public class MissingTenantException extends RuntimeException {
    public MissingTenantException() {
        super("No tenant: send the X-Tenant header");
    }
}
