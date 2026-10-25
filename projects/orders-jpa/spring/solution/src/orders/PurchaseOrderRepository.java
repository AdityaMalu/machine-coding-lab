package orders;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    /** Derived query: Spring Data writes it from the name, plus a count query for the Page. */
    Page<PurchaseOrder> findByCustomerEmail(String email, Pageable pageable);

    long countByCustomerEmailAndStatus(String email, OrderStatus status);

    /** The order with its customer, lines and their products, in one select. */
    @EntityGraph(attributePaths = {"customer", "lines", "lines.product"})
    Optional<PurchaseOrder> findWithLinesById(Long id);

    /** Locks the order row (SELECT ... FOR UPDATE) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findForUpdate(Long id);

    @Query("""
            select new orders.CustomerTotal(c.email, sum(l.quantity * l.unitPriceCents))
            from PurchaseOrder o join o.customer c join o.lines l
            where o.status <> orders.OrderStatus.CANCELLED and c.email in :emails
            group by c.email
            order by sum(l.quantity * l.unitPriceCents) desc""")
    List<CustomerTotal> totalsFor(Collection<String> emails);

    /** One row per order, computed by the database: no entities, no lazy loading, one statement. */
    @Query("""
            select new orders.OrderSummary(o.id, o.status, c.name,
                   coalesce(sum(l.quantity), 0), coalesce(sum(l.quantity * l.unitPriceCents), 0))
            from PurchaseOrder o join o.customer c left join o.lines l
            where c.email = :email
            group by o.id, o.status, c.name
            order by o.id""")
    List<OrderSummary> summariesFor(String email);
}
