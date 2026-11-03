package ledger;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

public record TransferRequest(@NotNull Long fromAccount, @NotNull Long toAccount, @NotNull BigDecimal amount) {}
