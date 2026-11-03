package reminders;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReminderRequest(@NotBlank @Email String email, @NotBlank String message, @NotNull Instant dueAt) {}
