package com.aideep.domain.node.consumer;

import com.aideep.domain.node.config.NodeEventConfiguration;
import com.aideep.domain.node.config.NodeEventProperties;
import com.aideep.domain.node.exception.NodeError;
import com.aideep.domain.node.service.NodeEventWorkResult;
import com.aideep.domain.node.service.NodeEventWorker;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

@Component
public class RedisNodeEventConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RedisNodeEventConsumer.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final StreamOperations<String, String, String> streamOperations;
    private final NodeEventProperties nodeEventProperties;
    private final NodeEventWorker nodeEventWorker;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> listenerContainer;
    private final ScheduledExecutorService scheduledExecutorService;
    private final Clock clock;
    private final Consumer consumer;

    private volatile boolean running;
    private volatile Subscription subscription;
    private ScheduledFuture<?> maintenanceTask;

    public RedisNodeEventConsumer(StringRedisTemplate stringRedisTemplate,
                                  NodeEventProperties nodeEventProperties,
                                  NodeEventWorker nodeEventWorker,
                                  StreamMessageListenerContainer<String, MapRecord<String, String, String>>
                                          listenerContainer,
                                  @Qualifier(NodeEventConfiguration.MAINTENANCE_EXECUTOR)
                                  ScheduledExecutorService scheduledExecutorService,
                                  ObjectProvider<Clock> clockProvider) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.streamOperations = stringRedisTemplate.opsForStream();
        this.nodeEventProperties = nodeEventProperties;
        this.nodeEventWorker = nodeEventWorker;
        this.listenerContainer = listenerContainer;
        this.scheduledExecutorService = scheduledExecutorService;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        this.consumer = Consumer.from(nodeEventProperties.consumerGroup(), resolveConsumerName(nodeEventProperties));
    }

    @Override
    public synchronized void start() {
        if (running || !nodeEventProperties.enabled()) {
            return;
        }
        running = true;
        maintenanceTask = scheduledExecutorService.scheduleWithFixedDelay(this::maintain,
                0, nodeEventProperties.reclaimInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (maintenanceTask != null) {
            maintenanceTask.cancel(true);
            maintenanceTask = null;
        }
        subscription = null;
        listenerContainer.stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void maintain() {
        if (!running) {
            return;
        }
        try {
            ensureSubscription();
            if (nodeEventWorker.isReady()) {
                reclaimPendingMessages();
            }
        } catch (RuntimeException exception) {
            log.error("Node event stream maintenance failed. stream={} group={} consumer={}",
                    nodeEventProperties.streamKey(), nodeEventProperties.consumerGroup(), consumer.getName(),
                    exception);
        }
    }

    private synchronized void ensureSubscription() {
        if (subscription != null) {
            return;
        }
        createConsumerGroup();
        var request = StreamMessageListenerContainer.StreamReadRequest
                .builder(StreamOffset.create(nodeEventProperties.streamKey(), ReadOffset.lastConsumed()))
                .consumer(consumer)
                .autoAcknowledge(false)
                .errorHandler(exception -> log.error(
                        "Node event stream read failed. stream={} group={} consumer={}",
                        nodeEventProperties.streamKey(), nodeEventProperties.consumerGroup(), consumer.getName(),
                        exception))
                .cancelOnError(exception -> false)
                .build();
        subscription = listenerContainer.register(request, this::handleRecord);
        listenerContainer.start();
        log.info("Node event stream consumer started. stream={} group={} consumer={}",
                nodeEventProperties.streamKey(), nodeEventProperties.consumerGroup(), consumer.getName());
    }

    private void createConsumerGroup() {
        try {
            streamOperations.createGroup(nodeEventProperties.streamKey(), ReadOffset.from("0-0"),
                    nodeEventProperties.consumerGroup());
        } catch (RuntimeException exception) {
            if (!isBusyGroup(exception)) {
                throw exception;
            }
        }
    }

    private void reclaimPendingMessages() {
        PendingMessages pendingMessages = streamOperations.pending(nodeEventProperties.streamKey(),
                nodeEventProperties.consumerGroup(), Range.unbounded(), nodeEventProperties.batchSize(),
                nodeEventProperties.reclaimMinIdle());
        for (var pendingMessage : pendingMessages) {
            List<MapRecord<String, String, String>> claimed = streamOperations.claim(
                    nodeEventProperties.streamKey(), nodeEventProperties.consumerGroup(), consumer.getName(),
                    nodeEventProperties.reclaimMinIdle(), pendingMessage.getId());
            claimed.forEach(this::handleRecord);
        }
    }

    private synchronized void handleRecord(MapRecord<String, String, String> record) {
        String data = record.getValue().get("data");
        NodeEventWorkResult result = data == null
                ? NodeEventWorkResult.permanentFailure(null, null, null, NodeError.INVALID_STREAM_ENTRY.getCode())
                : nodeEventWorker.process(data);
        switch (result.status()) {
            case PROCESSED -> acknowledge(record, result, "processed");
            case DEFERRED -> log.info(
                    "Node event deferred. entryId={} eventId={} eventType={} workspaceId={}",
                    record.getId(), result.eventId(), result.eventType(), result.nodeEventEnvelope().workspaceId());
            case PERMANENT_FAILURE -> moveToDlqAndAcknowledge(record, data, result,
                    Math.max(1, currentAttempts(record.getId())));
            case RETRYABLE_FAILURE -> retryOrMoveToDlq(record, data, result);
        }
    }

    private void retryOrMoveToDlq(MapRecord<String, String, String> record, String data, NodeEventWorkResult result) {
        long attempts = incrementAttempts(record.getId());
        if (attempts >= nodeEventProperties.maxAttempts()) {
            moveToDlqAndAcknowledge(record, data, result, attempts);
            return;
        }
        log.warn("Node event processing will be retried. entryId={} eventId={} eventType={} attempts={} errorCode={}",
                record.getId(), result.eventId(), result.eventType(), attempts, result.errorCode(), result.cause());
    }

    private void moveToDlqAndAcknowledge(MapRecord<String, String, String> record, String data,
                                         NodeEventWorkResult result, long attempts) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("sourceStream", nodeEventProperties.streamKey());
        fields.put("sourceEntryId", record.getId().getValue());
        fields.put("data", data == null ? "" : data);
        fields.put("eventId", valueOrEmpty(result.eventId()));
        fields.put("eventType", valueOrEmpty(result.eventType()));
        fields.put("failureCategory", result.status().name());
        fields.put("errorCode", valueOrEmpty(result.errorCode()));
        fields.put("attempts", Long.toString(attempts));
        fields.put("failedAt", clock.instant().toString());

        streamOperations.add(nodeEventProperties.dlqStreamKey(), fields,
                XAddOptions.trim(TrimOptions.maxLen(nodeEventProperties.dlqMaxLength()).approximate()));
        log.warn("Node event moved to DLQ. entryId={} eventId={} eventType={} workspaceId={} attempts={} "
                        + "errorCode={}", record.getId(), result.eventId(), result.eventType(), workspaceId(result),
                attempts, result.errorCode(), result.cause());
        acknowledge(record, result, "moved-to-dlq");
    }

    private void acknowledge(MapRecord<String, String, String> record, NodeEventWorkResult result, String outcome) {
        streamOperations.acknowledge(nodeEventProperties.streamKey(), nodeEventProperties.consumerGroup(),
                record.getId());
        clearAttempts(record.getId());
        log.info("Node event completed. entryId={} eventId={} eventType={} workspaceId={} outcome={}",
                record.getId(), result.eventId(), result.eventType(), workspaceId(result), outcome);
    }

    private long incrementAttempts(RecordId recordId) {
        Long attempts = stringRedisTemplate.opsForHash().increment(nodeEventProperties.retryKey(),
                recordId.getValue(), 1);
        return attempts == null ? 1 : attempts;
    }

    private long currentAttempts(RecordId recordId) {
        Object attempts = stringRedisTemplate.opsForHash().get(nodeEventProperties.retryKey(), recordId.getValue());
        return attempts == null ? 0 : Long.parseLong(attempts.toString());
    }

    private void clearAttempts(RecordId recordId) {
        try {
            stringRedisTemplate.opsForHash().delete(nodeEventProperties.retryKey(), recordId.getValue());
        } catch (RuntimeException exception) {
            log.warn("Failed to clear node event retry state. entryId={}", recordId, exception);
        }
    }

    private boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String resolveConsumerName(NodeEventProperties nodeEventProperties) {
        if (nodeEventProperties.consumerName() != null && !nodeEventProperties.consumerName().isBlank()) {
            return nodeEventProperties.consumerName();
        }
        return "aideep-" + UUID.randomUUID();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String workspaceId(NodeEventWorkResult result) {
        return result.nodeEventEnvelope() == null ? "" : result.nodeEventEnvelope().workspaceId().toString();
    }
}
