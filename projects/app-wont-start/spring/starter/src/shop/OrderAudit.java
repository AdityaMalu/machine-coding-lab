package shop;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** The history of one order. Prototype scope: each lookup gives a new, empty audit. */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class OrderAudit {
    private final String id = UUID.randomUUID().toString();
    private final List<String> events = new ArrayList<>();

    public String id() {
        return id;
    }

    public synchronized void record(String event) {
        events.add(event);
    }

    public synchronized List<String> events() {
        return List.copyOf(events);
    }
}
