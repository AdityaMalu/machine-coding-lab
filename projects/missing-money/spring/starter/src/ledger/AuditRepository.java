package ledger;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {
    List<AuditEntry> findByFromAccountIdOrderById(Long fromAccountId);
}
