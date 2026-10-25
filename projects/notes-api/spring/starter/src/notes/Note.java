package notes;

import java.time.Instant;
import java.util.List;

public record Note(long id, String title, String body, List<String> tags, Instant createdAt, Instant updatedAt) {}
