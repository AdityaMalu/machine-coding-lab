package notes;

public class DuplicateTitleException extends RuntimeException {
    public DuplicateTitleException(String title) {
        super("A note titled '" + title + "' already exists");
    }
}
