package com.aideep.domain.node.config;

import com.aideep.domain.node.repository.NodeCommandResultRepository;
import com.aideep.domain.node.service.NodeCommandResultPublisher;
import java.time.Clock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

@Configuration
@EnableConfigurationProperties({NodeEventProperties.class, NodeResultProperties.class})
public class NodeEventConfiguration {
    public static final String POLL_EXECUTOR = "nodeEventPollExecutor";
    public static final String MAINTENANCE_EXECUTOR = "nodeEventMaintenanceExecutor";

    public static final String RESULT_EXECUTOR = "nodeResultPublishExecutor";

    @Bean(name = RESULT_EXECUTOR, destroyMethod = "shutdown")
    ScheduledExecutorService nodeResultPublishExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("node-result-publish-").factory());
    }

    @Bean
    NodeCommandResultPublisher nodeCommandResultPublisher(NodeCommandResultRepository nodeCommandResultRepository,
            StringRedisTemplate stringRedisTemplate, NodeResultProperties nodeResultProperties,
            @Qualifier(RESULT_EXECUTOR) ScheduledExecutorService scheduledExecutorService,
            PlatformTransactionManager platformTransactionManager, Clock clock) {
        return new NodeCommandResultPublisher(nodeCommandResultRepository, stringRedisTemplate, nodeResultProperties,
                scheduledExecutorService, platformTransactionManager, clock);
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
