package payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The incident's acceptance tests. One app is shared, so every test uses its own order ids and keys. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentsIncidentTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeCardGateway gateway;
    @Autowired
    MailServer mail;
    @Autowired
    Ledger ledger;

    @AfterEach
    void mailBackUp() {
        mail.setDown(false);
    }

    static String uid(String p) {
        return p + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String body(String orderId, String unitPrice, int qty) {
        return "{\"orderId\":\"" + orderId + "\",\"unitPrice\":" + unitPrice + ",\"quantity\":" + qty + ",\"email\":\"sam@example.com\"}";
    }

    MvcResult pay(String key, String body) throws Exception {
        var req = post("/payments").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req).andReturn();
    }

    static String json(MvcResult r, String field) throws Exception {
        String s = r.getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + field + "\":\"?([^\",}]*)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    @Test
    @DisplayName("A payment returns 201 and charges the card once")
    void paysOnce() throws Exception {
        String order = uid("ord");
        MvcResult r = pay(uid("key"), body(order, "25.00", 2));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(gateway.chargesFor(order)).isEqualTo(1);
        assertThat(ledger.forOrder(order)).hasSize(1);
    }

    @Test
    @DisplayName("Amounts are exact: 19.99 x 3 charges 59.97, 0.10 x 3 charges 0.30")
    void exactAmounts() throws Exception {
        String a = uid("ord"), b = uid("ord");
        pay(uid("key"), body(a, "19.99", 3));
        pay(uid("key"), body(b, "0.10", 3));
        assertThat(gateway.lastAmountFor(a)).isEqualTo(new BigDecimal("59.97"));
        assertThat(gateway.lastAmountFor(b)).isEqualTo(new BigDecimal("0.30"));
        assertThat(ledger.totalFor(a)).isEqualTo(new BigDecimal("59.97"));
    }

    @Test
    @DisplayName("A retry with the same Idempotency-Key returns the same payment and doesn't charge again")
    void retryIsIdempotent() throws Exception {
        String order = uid("ord"), key = uid("key");
        MvcResult first = pay(key, body(order, "12.50", 1));
        MvcResult retry = pay(key, body(order, "12.50", 1));
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(retry, "id")).isEqualTo(json(first, "id"));
        assertThat(gateway.chargesFor(order)).isEqualTo(1);
    }

    @Test
    @DisplayName("Reusing an Idempotency-Key for a different request is rejected with 422")
    void keyReuseConflict() throws Exception {
        String order = uid("ord"), key = uid("key");
        pay(key, body(order, "12.50", 1));
        mvc.perform(post("/payments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body(order, "12.50", 2)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Idempotency key reused"));
        assertThat(gateway.chargesFor(order)).isEqualTo(1);
    }

    @Test
    @DisplayName("No Idempotency-Key header, or a bad body: 400")
    void badRequests() throws Exception {
        assertThat(pay(null, body(uid("ord"), "5.00", 1)).getResponse().getStatus()).isEqualTo(400);
        assertThat(pay(uid("key"), body(uid("ord"), "5.00", 0)).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("A declined card is 402 'Payment declined', with nothing in the ledger")
    void declined() throws Exception {
        String order = uid("ord");
        mvc.perform(post("/payments").header("Idempotency-Key", uid("key")).contentType(MediaType.APPLICATION_JSON).content(body(order, "6000.00", 2)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value("Payment declined"));
        assertThat(ledger.forOrder(order)).isEmpty();
    }

    @Test
    @DisplayName("A declined payment can be retried with the same Idempotency-Key")
    void retryAfterDecline() throws Exception {
        String order = uid("ord"), key = uid("key");
        assertThat(pay(key, body(order, "20000.00", 1)).getResponse().getStatus()).isEqualTo(402);
        assertThat(pay(key, body(order, "20000.00", 1)).getResponse().getStatus()).isEqualTo(402);
        assertThat(gateway.chargesFor(order)).isZero();
    }

    @Test
    @DisplayName("20 concurrent retries of one payment: exactly one charge, everyone gets the same payment")
    void concurrentRetries() throws Exception {
        String order = uid("ord"), key = uid("key");
        List<MvcResult> results = race(20, i -> pay(key, body(order, "30.00", 1)));
        Set<String> ids = new HashSet<>();
        for (MvcResult r : results) {
            assertThat(r.getResponse().getStatus()).isEqualTo(201);
            ids.add(json(r, "id"));
        }
        assertThat(gateway.chargesFor(order)).as("charges to the card").isEqualTo(1);
        assertThat(ids).as("payment ids returned").hasSize(1);
        assertThat(ledger.forOrder(order)).hasSize(1);
    }

    @Test
    @DisplayName("40 concurrent payments with different keys are all charged")
    void concurrentDistinct() throws Exception {
        String prefix = uid("ord");
        race(40, i -> pay(uid("key"), body(prefix + "-" + i, "1.00", 1)));
        int charged = 0;
        for (int i = 0; i < 40; i++) charged += gateway.chargesFor(prefix + "-" + i);
        assertThat(charged).isEqualTo(40);
    }

    @Test
    @DisplayName("The receipt is emailed in the background, not on the request thread")
    void receiptIsAsync() throws Exception {
        String order = uid("ord");
        pay(uid("key"), body(order, "8.00", 1));
        MailServer.Mail m = awaitMail(order);
        assertThat(m).as("receipt delivered").isNotNull();
        assertThat(m.thread()).as("thread that sent the receipt").isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("When the mail server is down, the payment still succeeds")
    void mailDown() throws Exception {
        mail.setDown(true);
        String order = uid("ord");
        MvcResult r = pay(uid("key"), body(order, "9.00", 1));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(gateway.chargesFor(order)).isEqualTo(1);
        assertThat(ledger.forOrder(order)).hasSize(1);
    }

    MailServer.Mail awaitMail(String order) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (MailServer.Mail m : mail.delivered()) if (m.subject().contains(order)) return m;
            Thread.sleep(50);
        }
        return null;
    }

    interface Call {
        MvcResult run(int i) throws Exception;
    }

    static List<MvcResult> race(int n, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.run(k);
                }));
            }
            go.countDown();
            List<MvcResult> out = new ArrayList<>();
            for (Future<MvcResult> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
