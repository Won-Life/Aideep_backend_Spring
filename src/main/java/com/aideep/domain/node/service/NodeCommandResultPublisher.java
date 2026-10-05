package com.aideep.domain.node.service;

import com.aideep.domain.node.config.NodeResultProperties;
import com.aideep.domain.node.repository.NodeCommandResultRepository;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DB row locks coordinate publishers. XADD/DB commit is deliberately at-least-once. */
public class NodeCommandResultPublisher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NodeCommandResultPublisher.class);
    private final NodeCommandResultRepository nodeCommandResultRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final NodeResultProperties nodeResultProperties;
    private final ScheduledExecutorService scheduledExecutorService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private volatile boolean running;
    private ScheduledFuture<?> publicationTask;

    public NodeCommandResultPublisher(NodeCommandResultRepository nodeCommandResultRepository,
                                      StringRedisTemplate stringRedisTemplate,
                                      NodeResultProperties nodeResultProperties,
                                      ScheduledExecutorService scheduledExecutorService,
                                      PlatformTransactionManager platformTransactionManager, Clock clock) {
        this.nodeCommandResultRepository = nodeCommandResultRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.nodeResultProperties = nodeResultProperties;
        this.scheduledExecutorService = scheduledExecutorService;
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (running || !nodeResultProperties.enabled()) {
            return;
        }
        running = true;
        publicationTask = scheduledExecutorService.scheduleWithFixedDelay(this::publishPending,
                0, nodeResultProperties.publishInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    public void publishPending() {
        try {
            for (int i = 0; i < nodeResultProperties.batchSize(); i++) {
                Boolean published = transactionTemplate.execute(status -> {
                    var next = nodeCommandResultRepository.lockNextUnpublished();
                    if (next.isEmpty()) {
                        return false;
                    }
                    var result = next.get();
                    var recordId = stringRedisTemplate.opsForStream().add(nodeResultProperties.streamKey(),
                            Map.of("data", result.getData()));
                    if (recordId == null) {
                        throw new IllegalStateException("Result XADD returned no record ID");
                    }
                    // XADD acceptance only: this does not imply AI consumption or outbox transaction commit.
                    log.info("Node command result published. stream={} entryId={} eventId={} commandEventId={}",
                            nodeResultProperties.streamKey(), recordId.getValue(), result.getId(),
                            result.getCommandEventId());
                    result.markPublished(clock.instant());
                    return true;
                });
                if (!Boolean.TRUE.equals(published)) {
                    return;
                }
            }
        } catch (RuntimeException exception) {
            // The DB transaction rolls back: a later tick or another instance retries the same immutable JSON.
            log.warn("Node command result publication failed; retained for retry. stream={}",
                    nodeResultProperties.streamKey(), exception);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (publicationTask != null) {
            publicationTask.cancel(false);
            publicationTask = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
