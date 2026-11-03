package ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The incident's acceptance tests. One app and database are shared, so every test opens its own accounts. */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerIncidentTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    AccountRepository accounts;
    @Autowired
    AuditRepository audit;
    @Autowired
    TransferRepository transfers;
    @Autowired
    TransferService service;

    long open(String balance) {
        return accounts.save(new Account("owner", new BigDecimal(balance))).getId();
    }

    BigDecimal balance(long id) {
        return accounts.findById(id).orElseThrow().getBalance();
    }

    int send(long from, long to, String amount) throws Exception {
        return mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccount\":" + from + ",\"toAccount\":" + to + ",\"amount\":" + amount + "}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("A transfer moves the money and is recorded")
    void moves() throws Exception {
        long a = open("100.00"), b = open("5.00");
        assertThat(send(a, b, "30.00")).isEqualTo(201);
        assertThat(balance(a)).isEqualByComparingTo("70.00");
        assertThat(balance(b)).isEqualByComparingTo("35.00");
        assertThat(transfers.findByFromAccountId(a)).hasSize(1);
        mvc.perform(get("/accounts/" + b)).andExpect(jsonPath("$.balance").value(35.00));
    }

    @Test
    @DisplayName("Not enough money: 409 'Insufficient funds', nothing moves")
    void insufficient() throws Exception {
        long a = open("10.00"), b = open("0.00");
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccount\":" + a + ",\"toAccount\":" + b + ",\"amount\":10.01}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
        assertThat(balance(a)).isEqualByComparingTo("10.00");
        assertThat(balance(b)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Transfer to an account that doesn't exist: 404, and the sender keeps their money")
    void unknownDestination() throws Exception {
        long a = open("50.00");
        assertThat(send(a, 987654321L, "20.00")).isEqualTo(404);
        assertThat(balance(a)).as("sender's balance after a failed transfer").isEqualByComparingTo("50.00");
        assertThat(transfers.findByFromAccountId(a)).isEmpty();
    }

    @Test
    @DisplayName("Zero, negative or same-account transfers: 400")
    void invalid() throws Exception {
        long a = open("50.00"), b = open("0.00");
        assertThat(send(a, b, "0.00")).as("0.00").isEqualTo(400);
        assertThat(send(a, b, "0")).as("0").isEqualTo(400);
        assertThat(send(a, b, "-5.00")).as("-5.00").isEqualTo(400);
        assertThat(send(a, a, "5.00")).as("same account").isEqualTo(400);
        assertThat(balance(a)).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Every attempt is audited: COMPLETED for a transfer, FAILED for one that was rolled back")
    void audited() throws Exception {
        long a = open("40.00"), b = open("0.00");
        send(a, b, "15.00");
        send(a, 987654321L, "5.00");
        send(a, b, "1000.00");
        List<AuditEntry> trail = audit.findByFromAccountIdOrderById(a);
        assertThat(trail).extracting(AuditEntry::getOutcome).containsExactly("COMPLETED", "FAILED", "FAILED");
        assertThat(balance(a)).isEqualByComparingTo("25.00");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("12 transfers out of one account at the same moment: none is lost")
    void concurrentFromOneAccount() throws Exception {
        long a = open("100.00"), b = open("0.00");
        race(12, i -> service.transfer(new TransferRequest(a, b, new BigDecimal("5.00"))));
        assertThat(balance(a)).as("sender").isEqualByComparingTo("40.00");
        assertThat(balance(b)).as("receiver").isEqualByComparingTo("60.00");
        assertThat(transfers.findByFromAccountId(a)).hasSize(12);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("Transfers in both directions at once all complete, and no money appears or disappears")
    void concurrentBothWays() throws Exception {
        long a = open("50.00"), b = open("50.00");
        race(12, i -> i % 2 == 0
                ? service.transfer(new TransferRequest(a, b, new BigDecimal("1.00")))
                : service.transfer(new TransferRequest(b, a, new BigDecimal("1.00"))));
        assertThat(balance(a).add(balance(b))).as("money in the two accounts").isEqualByComparingTo("100.00");
        assertThat(balance(a)).isEqualByComparingTo("50.00");
        assertThat(transfers.findByFromAccountId(a).size() + transfers.findByFromAccountId(b).size()).isEqualTo(12);
    }

    interface Task {
        Object run(int i) throws Exception;
    }

    static void race(int n, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.run(k);
                }));
            }
            go.countDown();
            for (Future<Object> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
    }
}
