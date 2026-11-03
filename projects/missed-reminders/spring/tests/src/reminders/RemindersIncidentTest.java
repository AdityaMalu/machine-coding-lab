package reminders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The incident's acceptance tests. Time is controlled by a test clock (the app must read time from its Clock
 * bean), and DispatchJob is run by the tests instead of the scheduler. Every test uses its own email addresses.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RemindersIncidentTest {

    /** A clock the tests can move. */
    static final class MovableClock extends Clock {
        volatile Instant now = Instant.parse("2031-03-01T09:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    MovableClock clock;
    @Autowired
    DispatchJob job;
    @Autowired
    Outbox outbox;
    @Autowired
    ReminderStore store;

    @BeforeEach
    void morning() {
        clock.now = Instant.parse("2031-03-01T09:00:00Z");
    }

    static String email(String p) {
        return p + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    String create(String tenant, String email, Instant due) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"message\":\"Standup\",\"dueAt\":\"" + due + "\"}";
        String json = mvc.perform(post("/reminders").header("X-Tenant", tenant).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
    }

    List<Outbox.Delivery> awaitDeliveries(String email, int n) throws InterruptedException {
        for (int i = 0; i < 120 && outbox.to(email).size() < n; i++) Thread.sleep(50); // up to 6 s
        Thread.sleep(300); // and nothing more arrives
        return outbox.to(email);
    }

    @Test
    @DisplayName("Reminders belong to the tenant that created them")
    void tenantScoped() throws Exception {
        String e = email("own");
        create("acme", e, clock.now.plusSeconds(3600));
        mvc.perform(get("/reminders").header("X-Tenant", "acme")).andExpect(jsonPath("$[?(@.email == '" + e + "')]").exists());
        mvc.perform(get("/reminders").header("X-Tenant", "globex")).andExpect(jsonPath("$[?(@.email == '" + e + "')]").doesNotExist());
    }

    @Test
    @DisplayName("A request without X-Tenant is rejected, even right after another tenant's request")
    void noTenant() throws Exception {
        create("acme", email("first"), clock.now.plusSeconds(60));
        String body = "{\"email\":\"" + email("anon") + "\",\"message\":\"x\",\"dueAt\":\"" + clock.now + "\"}";
        mvc.perform(post("/reminders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Tenant required"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Only reminders that are due go out; 'due' uses the app's clock")
    void sendsWhatIsDue() throws Exception {
        String later = email("later"), past = email("past");
        create("acme", past, clock.now.minus(Duration.ofMinutes(5)));
        create("acme", later, clock.now.plus(Duration.ofHours(2)));
        job.runOnce();
        assertThat(awaitDeliveries(past, 1)).hasSize(1);
        assertThat(outbox.to(later)).as("reminder due in 2 hours").isEmpty();
        clock.now = clock.now.plus(Duration.ofHours(3));
        job.runOnce();
        assertThat(awaitDeliveries(later, 1)).hasSize(1);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("A reminder due exactly now is sent on this run, not the next one")
    void dueExactlyNow() throws Exception {
        String e = email("exact");
        create("acme", e, clock.now);
        job.runOnce();
        assertThat(awaitDeliveries(e, 1)).hasSize(1);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Each tenant's reminders go out under that tenant's brand")
    void branding() throws Exception {
        String a = email("acme"), g = email("globex");
        create("acme", a, clock.now.minusSeconds(60));
        create("globex", g, clock.now.minusSeconds(60));
        for (int i = 0; i < 6; i++) create(i % 2 == 0 ? "acme" : "globex", email("noise"), clock.now.minusSeconds(30));
        job.runOnce();
        assertThat(awaitDeliveries(a, 1)).extracting(Outbox.Delivery::brandTenant).containsExactly("acme");
        assertThat(awaitDeliveries(g, 1)).extracting(Outbox.Delivery::brandTenant).containsExactly("globex");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("'Send now' delivers in the background under the requesting tenant's brand")
    void sendNow() throws Exception {
        String e = email("now");
        String id = create("globex", e, clock.now.plus(Duration.ofDays(1)));
        mvc.perform(post("/reminders/" + id + "/send").header("X-Tenant", "globex")).andExpect(status().isAccepted());
        List<Outbox.Delivery> d = awaitDeliveries(e, 1);
        assertThat(d).hasSize(1);
        assertThat(d.get(0).brandTenant()).isEqualTo("globex");
        assertThat(d.get(0).thread()).startsWith("notify-");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("If the provider fails, the reminder is retried on the next run, and delivered once")
    void retriesFailedDelivery() throws Exception {
        String e = email("flaky");
        create("acme", e, clock.now.minusSeconds(10));
        job.runOnce();
        Thread.sleep(500);
        assertThat(outbox.to(e)).as("after the failed first attempt").isEmpty();
        job.runOnce();
        assertThat(awaitDeliveries(e, 1)).as("deliveries after the retry").hasSize(1);
        job.runOnce();
        assertThat(awaitDeliveries(e, 1)).as("deliveries after another run").hasSize(1);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("Monday 9:00, 40 reminders are due at once: every one goes out on that run")
    void burst() throws Exception {
        String prefix = "burst-" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 40; i++) create("acme", prefix + "-" + i + "@example.com", clock.now.minusSeconds(1));
        try {
            job.runOnce();
        } catch (RuntimeException ignored) {
            // the run may report trouble, but the reminders must still go out
        }
        int delivered = 0;
        for (int wait = 0; wait < 200; wait++) { // up to 10 s for the whole burst
            delivered = 0;
            for (int i = 0; i < 40; i++) delivered += outbox.to(prefix + "-" + i + "@example.com").size();
            if (delivered >= 40) break;
            Thread.sleep(50);
        }
        assertThat(delivered).as("reminders delivered out of 40").isEqualTo(40);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("Two app instances running the job at the same time never send a reminder twice")
    void noDoubleSend() throws Exception {
        String prefix = "twice-" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 10; i++) create("acme", prefix + "-" + i + "@example.com", clock.now.minusSeconds(1));
        CompletableFuture<Void> a = CompletableFuture.runAsync(job::runOnce);
        CompletableFuture<Void> b = CompletableFuture.runAsync(job::runOnce);
        CompletableFuture.allOf(a, b).exceptionally(t -> null).get(60, TimeUnit.SECONDS);
        awaitDeliveries(prefix + "-9@example.com", 1);
        Thread.sleep(500);
        for (int i = 0; i < 10; i++) {
            assertThat(outbox.to(prefix + "-" + i + "@example.com")).as("deliveries of reminder " + i).hasSize(1);
        }
    }
}
