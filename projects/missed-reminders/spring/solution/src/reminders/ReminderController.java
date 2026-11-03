package reminders;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reminders")
public class ReminderController {

    private final ReminderService reminders;

    public ReminderController(ReminderService reminders) {
        this.reminders = reminders;
    }

    @PostMapping
    public ResponseEntity<Reminder> create(@Valid @RequestBody ReminderRequest request) {
        Reminder r = reminders.create(request);
        return ResponseEntity.created(URI.create("/reminders/" + r.id())).body(r);
    }

    @GetMapping
    public List<Reminder> mine() {
        return reminders.mine();
    }

    @PostMapping("/{id}/send")
    public ResponseEntity<Void> sendNow(@PathVariable String id) {
        return reminders.sendNow(id) ? ResponseEntity.accepted().build() : ResponseEntity.status(409).build();
    }
}
