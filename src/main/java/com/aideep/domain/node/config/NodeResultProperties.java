package com.aideep.domain.node.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("node.results")
public record NodeResultProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("onnode:ai:command-results:v1") String streamKey,
        @DefaultValue("1s") Duration publishInterval,
        @DefaultValue("100") int batchSize) {
    public NodeResultProperties {
        if (streamKey == null || streamKey.isBlank() || publishInterval == null
                || publishInterval.toMillis() <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("Node result stream, positive interval and batch size are required");
        }
    }
}
