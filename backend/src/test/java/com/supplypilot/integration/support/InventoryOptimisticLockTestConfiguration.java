package com.supplypilot.integration.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.supplypilot.inventory.dto.request.InventoryAdjustmentRequest;
import com.supplypilot.inventory.importing.repository.InventoryImportItemRepository;
import com.supplypilot.inventory.repository.InventoryRepository;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-only transaction choreography. It lets concurrent writers load the same managed
 * Inventory before a nominated writer commits, so the other writer performs a real stale
 * JPA update. Production beans never depend on this configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableAspectJAutoProxy
public class InventoryOptimisticLockTestConfiguration {

    @Bean
    InventoryConcurrencyProbe inventoryConcurrencyProbe() {
        return new InventoryConcurrencyProbe();
    }

    @Bean
    InventoryOptimisticLockTestAspect inventoryOptimisticLockTestAspect(
            InventoryConcurrencyProbe probe,
            TransactionTemplate transactionTemplate,
            InventoryRepository inventoryRepository,
            InventoryImportItemRepository itemRepository
    ) {
        return new InventoryOptimisticLockTestAspect(probe, transactionTemplate, inventoryRepository, itemRepository);
    }

    @Aspect
    static class InventoryOptimisticLockTestAspect {
        private final InventoryConcurrencyProbe probe;
        private final TransactionTemplate transactionTemplate;
        private final InventoryRepository inventoryRepository;
        private final InventoryImportItemRepository itemRepository;

        InventoryOptimisticLockTestAspect(
                InventoryConcurrencyProbe probe,
                TransactionTemplate transactionTemplate,
                InventoryRepository inventoryRepository,
                InventoryImportItemRepository itemRepository
        ) {
            this.probe = probe;
            this.transactionTemplate = transactionTemplate;
            this.inventoryRepository = inventoryRepository;
            this.itemRepository = itemRepository;
        }

        @Around("execution(void com.supplypilot.inventory.service.InventoryAdjustmentService.adjustInventory(..))"
                + " && args(materialId, request)")
        public Object synchronizeAdjustment(
                ProceedingJoinPoint joinPoint,
                Long materialId,
                InventoryAdjustmentRequest request
        ) throws Throwable {
            return executeAfterSharedRead(materialId, request.quantity().signum() > 0, joinPoint);
        }

        @Around("execution(void com.supplypilot.inventory.importing.service.InventoryImportConfirmService.confirm(..))"
                + " && args(batchId)")
        public Object synchronizeImport(ProceedingJoinPoint joinPoint, Long batchId) throws Throwable {
            InventoryConcurrencyProbe.Session session = probe.current();
            if (session == null) {
                return joinPoint.proceed();
            }
            Long materialId = itemRepository.findByBatchIdOrderByRowNumber(batchId).stream()
                    .findFirst()
                    .map(item -> item.getMaterial().getId())
                    .orElse(null);
            if (!session.matches(materialId)) {
                return joinPoint.proceed();
            }
            return executeAfterSharedRead(materialId, false, joinPoint);
        }

        private Object executeAfterSharedRead(Long materialId, boolean commitsFirst, ProceedingJoinPoint joinPoint) throws Throwable {
            InventoryConcurrencyProbe.Session session = probe.current();
            if (session == null || !session.matches(materialId)) {
                return joinPoint.proceed();
            }
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    long version = inventoryRepository.findByMaterialId(materialId).orElseThrow().getVersion();
                    session.recordRead(version);
                    session.awaitBothReads();
                    if (!commitsFirst) {
                        session.awaitFirstCommit();
                    }
                    try {
                        joinPoint.proceed();
                    } catch (Throwable exception) {
                        throw new JoinPointFailure(exception);
                    }
                });
            } catch (JoinPointFailure failure) {
                throw failure.getCause();
            }
            if (commitsFirst) {
                session.signalFirstCommit();
            }
            return null;
        }
    }

    public static final class InventoryConcurrencyProbe {
        private final AtomicReference<Session> active = new AtomicReference<>();

        public Session open(Long materialId) {
            Session session = new Session(materialId);
            if (!active.compareAndSet(null, session)) {
                throw new IllegalStateException("A concurrency session is already active.");
            }
            return session;
        }

        Session current() {
            return active.get();
        }

        public final class Session implements AutoCloseable {
            private static final Duration TIMEOUT = Duration.ofSeconds(20);

            private final Long materialId;
            private final CountDownLatch bothRead = new CountDownLatch(2);
            private final CountDownLatch firstCommitted = new CountDownLatch(1);
            private final ConcurrentLinkedQueue<Long> readVersions = new ConcurrentLinkedQueue<>();

            private Session(Long materialId) {
                this.materialId = materialId;
            }

            boolean matches(Long candidateMaterialId) {
                return materialId.equals(candidateMaterialId);
            }

            void recordRead(long version) {
                readVersions.add(version);
                bothRead.countDown();
            }

            void awaitBothReads() {
                await(bothRead, "both writers to read Inventory");
            }

            void awaitFirstCommit() {
                await(firstCommitted, "the successful writer to commit");
            }

            void signalFirstCommit() {
                firstCommitted.countDown();
            }

            public List<Long> readVersions() {
                return List.copyOf(readVersions);
            }

            @Override
            public void close() {
                active.compareAndSet(this, null);
                firstCommitted.countDown();
            }

            private static void await(CountDownLatch latch, String condition) {
                try {
                    if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("Timed out waiting for " + condition + ".");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for " + condition + ".", exception);
                }
            }
        }
    }

    private static final class JoinPointFailure extends RuntimeException {
        private JoinPointFailure(Throwable cause) {
            super(cause);
        }

        @Override
        public synchronized Throwable getCause() {
            return super.getCause();
        }
    }
}
