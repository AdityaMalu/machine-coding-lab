package notes;

import java.util.List;

// TODO: add Jakarta validation constraints (see the README): title required, not blank, max 100;
//       body max 5000; at most 5 tags, each not blank and max 20.
public record NoteRequest(String title, String body, List<String> tags) {}
