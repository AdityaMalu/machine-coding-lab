package notes;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** Turns the app's exceptions into RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(NoteNotFoundException.class)
    ProblemDetail notFound(NoteNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Note not found", e.getMessage());
    }

    @ExceptionHandler(DuplicateTitleException.class)
    ProblemDetail duplicate(DuplicateTitleException e) {
        return problem(HttpStatus.CONFLICT, "Duplicate title", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(f.getField(), f.getDefaultMessage());
        }
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "Invalid request", "Some fields are invalid");
        p.setProperty("errors", errors);
        return p;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidParams(HandlerMethodValidationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "Some parameters are invalid");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
