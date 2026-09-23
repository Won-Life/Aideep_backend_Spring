package com.aideep.domain.node.config;

import com.aideep.domain.node.service.DeferredNodeCommandProcessor;
import com.aideep.domain.node.service.NodeCommandProcessor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

@Configuration
@EnableConfigurationProperties(NodeEventProperties.class)
public class NodeEventConfiguration {
    public static final String POLL_EXECUTOR = "nodeEventPollExecutor";
    public static final String MAINTENANCE_EXECUTOR = "nodeEventMaintenanceExecutor";

    @Bean
    @ConditionalOnMissingBean(NodeCommandProcessor.class)
    NodeCommandProcessor nodeCommandProcessor() {
        return new DeferredNodeCommandProcessor();
    }

    @Bean(name = POLL_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService nodeEventPollExecutor() {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform().name("node-event-poll-").factory());
    }

    @Bean(name = MAINTENANCE_EXECUTOR, destroyMethod = "shutdown")
    ScheduledExecutorService nodeEventMaintenanceExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("node-event-maintenance-").factory());
    }

    @Bean(destroyMethod = "stop")
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> nodeEventListenerContainer(
            RedisConnectionFactory redisConnectionFactory, NodeEventProperties nodeEventProperties,
            @Qualifier(POLL_EXECUTOR) ExecutorService nodeEventPollExecutor) {
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .<String, MapRecord<String, String, String>>builder()
                .serializer(StringRedisSerializer.UTF_8)
                .batchSize(nodeEventProperties.batchSize())
                .pollTimeout(nodeEventProperties.blockTimeout())
                .executor(nodeEventPollExecutor)
                .autoStartup(false)
                .build();
        return StreamMessageListenerContainer.create(redisConnectionFactory, options);
    }
}
