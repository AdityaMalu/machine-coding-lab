package notes;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * In-memory notes. Reads go straight to the concurrent maps; writes are synchronized so the
 * title check and the insert (or rename) happen as one step.
 */
@Service
public class NoteService {

    private final Map<Long, Note> notes = new ConcurrentHashMap<>();
    private final Map<String, Long> idByTitle = new ConcurrentHashMap<>();
    private final Clock clock;
    private long lastId;

    public NoteService(Clock clock) {
        this.clock = clock;
    }

    public synchronized Note create(NoteRequest request) {
        String title = request.title().strip();
        String key = key(title);
        if (idByTitle.containsKey(key)) throw new DuplicateTitleException(title);
        long id = ++lastId;
        Instant now = clock.instant();
        Note note = new Note(id, title, body(request), tags(request), now, now);
        notes.put(id, note);
        idByTitle.put(key, id);
        return note;
    }

    public Note get(long id) {
        Note note = notes.get(id);
        if (note == null) throw new NoteNotFoundException(id);
        return note;
    }

    public synchronized Note update(long id, NoteRequest request) {
        Note old = get(id);
        String title = request.title().strip();
        String key = key(title);
        Long owner = idByTitle.get(key);
        if (owner != null && owner != id) throw new DuplicateTitleException(title);
        Note note = new Note(id, title, body(request), tags(request), old.createdAt(), clock.instant());
        idByTitle.remove(key(old.title()));
        idByTitle.put(key, id);
        notes.put(id, note);
        return note;
    }

    public synchronized void delete(long id) {
        Note old = notes.remove(id);
        if (old == null) throw new NoteNotFoundException(id);
        idByTitle.remove(key(old.title()));
    }

    public NotePage list(String tag, int page, int size) {
        String wanted = tag == null || tag.isBlank() ? null : tag.strip().toLowerCase(Locale.ROOT);
        List<Note> matching = new ArrayList<>(notes.values().stream()
                .filter(n -> wanted == null || n.tags().contains(wanted))
                .sorted(Comparator.comparingLong(Note::id))
                .toList());
        int from = (int) Math.min((long) page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        return new NotePage(List.copyOf(matching.subList(from, to)), page, size, matching.size());
    }

    private static String key(String title) {
        return title.strip().toLowerCase(Locale.ROOT);
    }

    private static String body(NoteRequest request) {
        return request.body() == null ? "" : request.body();
    }

    private static List<String> tags(NoteRequest request) {
        if (request.tags() == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : request.tags()) out.add(t.strip().toLowerCase(Locale.ROOT));
        return List.copyOf(out);
    }
}
