package com.supplypilot.integration.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.StaleObjectStateException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-only transaction choreography for a real concurrent receive. Each request owns
 * a READ_COMMITTED transaction and persistence context, and both preload the same PO
 * before either request may continue. One request is then allowed to commit before the
 * stale request resumes.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableAspectJAutoProxy
public class PurchaseOrderReceivingConcurrencyTestConfiguration {

    @Bean
    PurchaseOrderReceivingProbe purchaseOrderReceivingProbe() {
        return new PurchaseOrderReceivingProbe();
    }

    @Bean
    PurchaseOrderReceivingTestAspect purchaseOrderReceivingTestAspect(
            PurchaseOrderReceivingProbe probe,
            PlatformTransactionManager transactionManager,
            PurchaseOrderRepository purchaseOrderRepository
    ) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new PurchaseOrderReceivingTestAspect(probe, transactionTemplate, purchaseOrderRepository);
    }

    @Aspect
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class PurchaseOrderReceivingTestAspect {
        private final PurchaseOrderReceivingProbe probe;
        private final TransactionTemplate transactionTemplate;
        private final PurchaseOrderRepository purchaseOrderRepository;

        PurchaseOrderReceivingTestAspect(
                PurchaseOrderReceivingProbe probe,
                TransactionTemplate transactionTemplate,
                PurchaseOrderRepository purchaseOrderRepository
        ) {
            this.probe = probe;
            this.transactionTemplate = transactionTemplate;
            this.purchaseOrderRepository = purchaseOrderRepository;
        }

        @Around("execution(* com.supplypilot.procurement.service.ReceivingService.receive(..))")
        public Object synchronizeReceive(ProceedingJoinPoint joinPoint) throws Throwable {
            Long purchaseOrderId = (Long) joinPoint.getArgs()[0];
            PurchaseOrderReceivingProbe.Session session = probe.current();
            if (session == null || !session.matches(purchaseOrderId)) {
                return joinPoint.proceed();
            }

            AtomicBoolean commitsFirst = new AtomicBoolean();
            try {
                return transactionTemplate.execute(status -> {
                    PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(purchaseOrderId).orElseThrow();
                    session.recordRead(purchaseOrder.getVersion(), purchaseOrder.getStatus());
                    session.awaitBothReads();

                    commitsFirst.set(session.claimCommitter());
                    if (!commitsFirst.get()) {
                        session.awaitFirstCommit();
                    }
                    try {
                        return joinPoint.proceed();
                    } catch (Throwable exception) {
                        throw new JoinPointFailure(exception);
                    }
                });
            } catch (JoinPointFailure failure) {
                session.recordFailure(failure.getCause());
                throw failure.getCause();
            } catch (RuntimeException exception) {
                session.recordFailure(exception);
                throw exception;
            } finally {
                if (commitsFirst.get()) {
                    session.signalFirstCommit();
                }
            }
        }
    }

    public record PurchaseOrderRead(long version, PurchaseOrderStatus status) {
    }

    public static final class PurchaseOrderReceivingProbe {
        private final AtomicReference<Session> active = new AtomicReference<>();

        public Session open(Long purchaseOrderId) {
            Session session = new Session(purchaseOrderId);
            if (!active.compareAndSet(null, session)) {
                throw new IllegalStateException("A receiving concurrency session is already active.");
            }
            return session;
        }

        Session current() {
            return active.get();
        }

        public final class Session implements AutoCloseable {
            private static final Duration TIMEOUT = Duration.ofSeconds(20);

            private final Long purchaseOrderId;
            private final CountDownLatch bothRead = new CountDownLatch(2);
            private final CountDownLatch firstCommitted = new CountDownLatch(1);
            private final AtomicBoolean committerClaimed = new AtomicBoolean();
            private final ConcurrentLinkedQueue<PurchaseOrderRead> reads = new ConcurrentLinkedQueue<>();
            private final ConcurrentLinkedQueue<String> failureTypes = new ConcurrentLinkedQueue<>();

            private Session(Long purchaseOrderId) {
                this.purchaseOrderId = purchaseOrderId;
            }

            boolean matches(Long candidatePurchaseOrderId) {
                return purchaseOrderId.equals(candidatePurchaseOrderId);
            }

            void recordRead(long version, PurchaseOrderStatus status) {
                reads.add(new PurchaseOrderRead(version, status));
                bothRead.countDown();
            }

            void awaitBothReads() {
                await(bothRead, "both receiving requests to read PurchaseOrder");
            }

            boolean claimCommitter() {
                return committerClaimed.compareAndSet(false, true);
            }

            void awaitFirstCommit() {
                await(firstCommitted, "the successful receiving request to commit");
            }

            void signalFirstCommit() {
                firstCommitted.countDown();
            }

            void recordFailure(Throwable failure) {
                StringBuilder description = new StringBuilder();
                Throwable current = failure;
                while (current != null) {
                    if (!description.isEmpty()) {
                        description.append(" -> ");
                    }
                    description.append(current.getClass().getName());
                    if (current instanceof ObjectOptimisticLockingFailureException optimisticFailure
                            && optimisticFailure.getPersistentClass() != null) {
                        description.append(':').append(optimisticFailure.getPersistentClass().getName());
                    }
                    if (current instanceof StaleObjectStateException staleObjectStateException) {
                        description.append(':').append(staleObjectStateException.getEntityName());
                    }
                    current = current.getCause();
                }
                failureTypes.add(description.toString());
            }

            public List<PurchaseOrderRead> reads() {
                return List.copyOf(reads);
            }

            public List<String> failureTypes() {
                return List.copyOf(failureTypes);
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
