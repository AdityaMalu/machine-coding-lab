package notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The spec for the Notes API. All tests share one running app (one Spring context), so each test
 * uses its own unique titles and tags instead of assuming an empty store.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotesApiTest {

    @Autowired
    MockMvc mvc;

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String json(String title, String body, String... tags) {
        StringBuilder sb = new StringBuilder("{");
        if (title != null) sb.append("\"title\":").append(quote(title));
        if (body != null) sb.append(sb.length() > 1 ? "," : "").append("\"body\":").append(quote(body));
        if (tags.length > 0) {
            sb.append(sb.length() > 1 ? "," : "").append("\"tags\":[");
            for (int i = 0; i < tags.length; i++) sb.append(i > 0 ? "," : "").append(quote(tags[i]));
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    long create(String body) throws Exception {
        MvcResult r = mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String location = r.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    @DisplayName("POST /notes returns 201, a Location header and the stored note")
    void createsNote() throws Exception {
        String title = unique("Groceries");
        mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON)
                        .content(json("  " + title + "  ", "milk, eggs", "Home", " shopping ", "home")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/notes/\\d+")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.body").value("milk, eggs"))
                .andExpect(jsonPath("$.tags", hasSize(2)))
                .andExpect(jsonPath("$.tags[0]").value("home"))
                .andExpect(jsonPath("$.tags[1]").value("shopping"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("Body and tags are optional")
    void optionalFields() throws Exception {
        long id = create(json(unique("Bare"), null));
        mvc.perform(get("/notes/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(""))
                .andExpect(jsonPath("$.tags", hasSize(0)));
    }

    @Test
    @DisplayName("GET /notes/{id} returns the note")
    void getsNote() throws Exception {
        String title = unique("Read me");
        long id = create(json(title, "hello"));
        mvc.perform(get("/notes/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value(title));
    }

    @Test
    @DisplayName("Unknown id: 404 ProblemDetail titled 'Note not found'")
    void unknownIdIs404() throws Exception {
        mvc.perform(get("/notes/987654321"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Note not found"));
    }

    @Test
    @DisplayName("Blank or missing title: 400 with errors.title")
    void blankTitleIs400() throws Exception {
        for (String body : List.of(json("   ", "x"), json(null, "x"))) {
            mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Invalid request"))
                    .andExpect(jsonPath("$.errors.title").isNotEmpty());
        }
    }

    @Test
    @DisplayName("Title over 100 characters or body over 5000: 400")
    void tooLongIs400() throws Exception {
        mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content(json("t".repeat(101), "x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").isNotEmpty());
        mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content(json(unique("Long"), "b".repeat(5001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.body").isNotEmpty());
        create(json(unique("t".repeat(80)), "b".repeat(5000))); // the limits themselves are fine
    }

    @Test
    @DisplayName("More than 5 tags, or a blank tag: 400")
    void badTagsAre400() throws Exception {
        mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON)
                        .content(json(unique("Tags"), "x", "a", "b", "c", "d", "e", "f")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tags").isNotEmpty());
        mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content(json(unique("Tags"), "x", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['tags[0]']").isNotEmpty());
    }

    @Test
    @DisplayName("Malformed JSON: 400")
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content("{\"title\": "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Duplicate title, ignoring case and spaces: 409 'Duplicate title'")
    void duplicateTitleIs409() throws Exception {
        String title = unique("Plan");
        create(json(title, "a"));
        mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content(json("  " + title.toUpperCase() + " ", "b")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Duplicate title"));
    }

    @Test
    @DisplayName("PUT replaces fields, keeps createdAt, moves updatedAt forward")
    void updatesNote() throws Exception {
        long id = create(json(unique("Draft"), "v1", "work"));
        String created = mvc.perform(get("/notes/" + id)).andReturn().getResponse().getContentAsString();
        Instant createdAt = Instant.parse(created.replaceAll("(?s).*\"createdAt\":\"([^\"]+)\".*", "$1"));
        Thread.sleep(5);
        String newTitle = unique("Final");
        String updated = mvc.perform(put("/notes/" + id).contentType(MediaType.APPLICATION_JSON).content(json(newTitle, "v2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value(newTitle))
                .andExpect(jsonPath("$.body").value("v2"))
                .andExpect(jsonPath("$.tags", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        Instant createdAfter = Instant.parse(updated.replaceAll("(?s).*\"createdAt\":\"([^\"]+)\".*", "$1"));
        Instant updatedAt = Instant.parse(updated.replaceAll("(?s).*\"updatedAt\":\"([^\"]+)\".*", "$1"));
        assertThat(createdAfter).isEqualTo(createdAt);
        assertThat(updatedAt).isAfter(createdAt);
    }

    @Test
    @DisplayName("PUT: 404 for an unknown id, 400 for an invalid body")
    void updateErrors() throws Exception {
        mvc.perform(put("/notes/987654321").contentType(MediaType.APPLICATION_JSON).content(json(unique("X"), "y")))
                .andExpect(status().isNotFound());
        long id = create(json(unique("Valid"), "y"));
        mvc.perform(put("/notes/" + id).contentType(MediaType.APPLICATION_JSON).content(json("", "y")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT to another note's title is 409; keeping your own title in another case is fine")
    void updateTitleConflicts() throws Exception {
        String a = unique("Alpha");
        String b = unique("Beta");
        create(json(a, "1"));
        long idB = create(json(b, "2"));
        mvc.perform(put("/notes/" + idB).contentType(MediaType.APPLICATION_JSON).content(json(a, "2")))
                .andExpect(status().isConflict());
        mvc.perform(put("/notes/" + idB).contentType(MediaType.APPLICATION_JSON).content(json(b.toUpperCase(), "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(b.toUpperCase()));
    }

    @Test
    @DisplayName("Renaming frees the old title")
    void renameFreesOldTitle() throws Exception {
        String old = unique("Old");
        long id = create(json(old, "1"));
        mvc.perform(put("/notes/" + id).contentType(MediaType.APPLICATION_JSON).content(json(unique("New"), "1")))
                .andExpect(status().isOk());
        create(json(old, "reused"));
    }

    @Test
    @DisplayName("DELETE returns 204, then the note is gone and its title can be reused")
    void deletesNote() throws Exception {
        String title = unique("Temp");
        long id = create(json(title, "x"));
        mvc.perform(delete("/notes/" + id)).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(get("/notes/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/notes/" + id)).andExpect(status().isNotFound());
        long again = create(json(title, "x"));
        assertThat(again).as("ids are never reused").isGreaterThan(id);
    }

    @Test
    @DisplayName("GET /notes filters by tag (any case), orders by id and pages")
    void listsByTagWithPaging() throws Exception {
        String tag = unique("t").toLowerCase();
        long first = create(json(unique("One"), "1", tag));
        create(json(unique("Other"), "x", "unrelated"));
        long second = create(json(unique("Two"), "2", tag.toUpperCase()));
        long third = create(json(unique("Three"), "3", "misc", tag));
        mvc.perform(get("/notes").param("tag", tag.toUpperCase()).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(first))
                .andExpect(jsonPath("$.items[1].id").value(second));
        mvc.perform(get("/notes").param("tag", tag).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(third));
        mvc.perform(get("/notes").param("tag", tag).param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    @DisplayName("Page size outside 1..50 or a negative page: 400 'Invalid request'")
    void badPagingIs400() throws Exception {
        for (String[] p : List.of(new String[] {"size", "0"}, new String[] {"size", "51"}, new String[] {"page", "-1"})) {
            mvc.perform(get("/notes").param(p[0], p[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid request"));
        }
        mvc.perform(get("/notes").param("size", "50")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("50 concurrent creates of one title: exactly one 201, the rest 409")
    void concurrentDuplicateCreates() throws Exception {
        String title = unique("Race");
        List<Integer> statuses = race(50, i -> json(i % 2 == 0 ? title : title.toUpperCase(), "x"));
        assertThat(count(statuses, 201)).as("201 responses, statuses were " + summary(statuses)).isEqualTo(1);
        assertThat(count(statuses, 409)).as("409 responses, statuses were " + summary(statuses)).isEqualTo(49);
    }

    @Test
    @DisplayName("50 concurrent creates of different titles all succeed with distinct ids")
    void concurrentDistinctCreates() throws Exception {
        String prefix = unique("Many");
        String tag = unique("batch").toLowerCase();
        List<Integer> statuses = race(50, i -> json(prefix + "-" + i, "x", tag));
        assertThat(count(statuses, 201)).as("201 responses, statuses were " + summary(statuses)).isEqualTo(50);
        mvc.perform(get("/notes").param("tag", tag).param("size", "50"))
                .andExpect(jsonPath("$.total").value(50))
                .andExpect(jsonPath("$.items", hasSize(50)));
    }

    @Test
    @DisplayName("Controllers and services use constructor injection (no @Autowired fields)")
    void constructorInjection() {
        for (Class<?> type : List.of(NoteController.class, NoteService.class)) {
            for (Field f : type.getDeclaredFields()) {
                assertThat(f.isAnnotationPresent(Autowired.class)).as(type.getSimpleName() + "." + f.getName()).isFalse();
            }
        }
    }

    static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    /** e.g. {201=1, 409=49} */
    static String summary(List<Integer> statuses) {
        return new java.util.TreeMap<>(statuses.stream().collect(
                java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting()))).toString();
    }

    interface Body {
        String of(int i);
    }

    /** Sends n POSTs at the same moment from n threads and returns their status codes. */
    List<Integer> race(int n, Body body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                Callable<Integer> call = () -> {
                    go.await();
                    return mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content(body.of(k)))
                            .andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
