package notes;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TODO: the endpoints from the README.
 *   POST   /notes          201 + Location
 *   GET    /notes/{id}
 *   GET    /notes?tag=&page=0&size=20
 *   PUT    /notes/{id}
 *   DELETE /notes/{id}     204
 */
@RestController
@RequestMapping("/notes")
public class NoteController {

    private final NoteService service;

    public NoteController(NoteService service) {
        this.service = service;
    }
}
