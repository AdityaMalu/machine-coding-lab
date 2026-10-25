package notes;

import java.util.List;

public record NotePage(List<Note> items, int page, int size, long total) {}
