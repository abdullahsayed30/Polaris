package io.polaris.inventory.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event from OutboxEvent event
            where event.status in (io.polaris.inventory.adapter.out.persistence.OutboxStatus.PENDING,
                    io.polaris.inventory.adapter.out.persistence.OutboxStatus.RETRY)
              and event.nextAttemptAt <= :now
            order by event.occurredAt, event.eventId
            """)
    List<OutboxEvent> findReady(Instant now, Pageable pageable);
}
