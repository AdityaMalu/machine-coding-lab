package notes;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NoteRequest(
        @NotBlank @Size(max = 100) String title,
        @Size(max = 5000) String body,
        @Size(max = 5) List<@NotBlank @Size(max = 20) String> tags) {}
