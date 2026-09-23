package com.aideep.domain.node.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("node.events")
public record NodeEventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("onnode:ai:commands:v1") String streamKey,
        @DefaultValue("onnode:ai:commands:dlq:v1") String dlqStreamKey,
        @DefaultValue("onnode:ai:commands:retries:v1") String retryKey,
        @DefaultValue("aideep-node-command-workers-v1") String consumerGroup,
        @DefaultValue("") String consumerName,
        @DefaultValue("1") int batchSize,
        @DefaultValue("2s") Duration blockTimeout,
        @DefaultValue("30s") Duration reclaimMinIdle,
        @DefaultValue("30s") Duration reclaimInterval,
        @DefaultValue("5") long maxAttempts,
        @DefaultValue("100000") long dlqMaxLength
) {
    public NodeEventProperties {
        requireText(streamKey, "streamKey");
        requireText(dlqStreamKey, "dlqStreamKey");
        requireText(retryKey, "retryKey");
        requireText(consumerGroup, "consumerGroup");
        requirePositive(batchSize, "batchSize");
        requirePositive(blockTimeout, "blockTimeout");
        requirePositive(reclaimMinIdle, "reclaimMinIdle");
        requirePositive(reclaimInterval, "reclaimInterval");
        requirePositive(maxAttempts, "maxAttempts");
        requirePositive(dlqMaxLength, "dlqMaxLength");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
