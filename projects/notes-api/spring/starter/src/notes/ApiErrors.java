package notes;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * TODO: turn exceptions into ProblemDetail responses (see the README):
 *   NoteNotFoundException -> 404, DuplicateTitleException -> 409,
 *   MethodArgumentNotValidException -> 400 with an "errors" map,
 *   HandlerMethodValidationException -> 400.
 */
@RestControllerAdvice
public class ApiErrors {
}
