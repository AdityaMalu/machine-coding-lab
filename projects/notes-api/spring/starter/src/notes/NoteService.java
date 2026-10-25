package notes;

import java.time.Clock;

import org.springframework.stereotype.Service;

/** In-memory notes. Must be thread-safe: many requests call it at the same time. */
@Service
public class NoteService {

    private final Clock clock;

    public NoteService(Clock clock) {
        this.clock = clock;
    }

    public Note create(NoteRequest request) {
        throw new UnsupportedOperationException("TODO");
    }

    public Note get(long id) {
        throw new UnsupportedOperationException("TODO");
    }

    public Note update(long id, NoteRequest request) {
        throw new UnsupportedOperationException("TODO");
    }

    public void delete(long id) {
        throw new UnsupportedOperationException("TODO");
    }

    public NotePage list(String tag, int page, int size) {
        throw new UnsupportedOperationException("TODO");
    }
}
