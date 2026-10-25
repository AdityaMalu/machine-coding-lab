package orders;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * TODO: every method below is a placeholder (a default method is just Java; Spring Data leaves it alone).
 * Turn each into a real query: delete the body and the "default" keyword so Spring Data derives the query
 * from the name, or add @Query / @EntityGraph / @Lock as the README describes.
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    /** A page of the customer's orders (derived query). */
    default Page<PurchaseOrder> findByCustomerEmail(String email, Pageable pageable) {
        throw new UnsupportedOperationException("TODO");
    }

    /** Derived query. */
    default long countByCustomerEmailAndStatus(String email, OrderStatus status) {
        throw new UnsupportedOperationException("TODO");
    }

    /** The order with its customer, lines and their products, in one select. */
    default Optional<PurchaseOrder> findWithLinesById(Long id) {
        throw new UnsupportedOperationException("TODO");
    }

    /** The order, with its row locked until the transaction ends. */
    default Optional<PurchaseOrder> findForUpdate(Long id) {
        throw new UnsupportedOperationException("TODO");
    }

    /** Spend per customer over orders that aren't cancelled, biggest first (JPQL constructor expression). */
    default List<CustomerTotal> totalsFor(Collection<String> emails) {
        throw new UnsupportedOperationException("TODO");
    }

    /** One OrderSummary per order of the customer, ordered by id, in one statement. */
    default List<OrderSummary> summariesFor(String email) {
        throw new UnsupportedOperationException("TODO");
    }
}
