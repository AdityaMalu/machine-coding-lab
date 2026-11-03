package reminders;

/**
 * The tenant (customer company) the current work belongs to, per thread. Set by TenantFilter for web requests;
 * anything that hands work to another thread must carry it across, and pooled threads must never keep it.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenant) {
        CURRENT.set(tenant);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String require() {
        String t = CURRENT.get();
        if (t == null) throw new MissingTenantException();
        return t;
    }

    /** Wraps a task so it runs with the submitting thread's tenant, and leaves the worker thread as it was. */
    public static Runnable propagate(Runnable task) {
        String tenant = get();
        return () -> {
            String previous = get();
            if (tenant == null) clear();
            else set(tenant);
            try {
                task.run();
            } finally {
                if (previous == null) clear();
                else set(previous);
            }
        };
    }
}
