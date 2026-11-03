package ledger;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LedgerController {

    private final TransferService transfers;
    private final AccountRepository accounts;

    public LedgerController(TransferService transfers, AccountRepository accounts) {
        this.transfers = transfers;
        this.accounts = accounts;
    }

    @PostMapping("/transfers")
    public ResponseEntity<Map<String, Object>> transfer(@Valid @RequestBody TransferRequest request) {
        Transfer t = transfers.transfer(request);
        return ResponseEntity.created(URI.create("/transfers/" + t.getId()))
                .body(Map.of("id", t.getId(), "from", t.getFromAccountId(), "to", t.getToAccountId(), "amount", t.getAmount()));
    }

    @GetMapping("/accounts/{id}")
    public Map<String, Object> account(@PathVariable Long id) {
        Account a = accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        BigDecimal balance = a.getBalance();
        return Map.of("id", a.getId(), "owner", a.getOwner(), "balance", balance);
    }
}
