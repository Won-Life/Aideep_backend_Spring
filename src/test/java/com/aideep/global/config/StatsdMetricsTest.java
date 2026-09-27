package com.aideep.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.statsd.StatsdMeterRegistry;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.statsd.StatsdMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StatsdMetricsTest {
    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                    StatsdMetricsExportAutoConfiguration.class))
            .withPropertyValues("spring.profiles.active=prod", "DD_METRICS_ENABLED=false");

    @Test
    void disabledExporterDoesNotCreateUdpRegistry() {
        applicationContextRunner.run(context -> assertThat(context).doesNotHaveBean(StatsdMeterRegistry.class));
    }

    @Test
    void sendsDatadogPacketsWithConfiguredCommonTags() throws Exception {
        try (DatagramSocket datagramSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            datagramSocket.setSoTimeout(10000);
            applicationContextRunner.withPropertyValues("DD_METRICS_ENABLED=true", "DD_AGENT_HOST=127.0.0.1",
                    "DD_DOGSTATSD_PORT=" + datagramSocket.getLocalPort(), "DD_SERVICE=observability-test",
                    "DD_ENV=test", "DD_VERSION=integration", "management.statsd.metrics.export.buffered=false")
                    .run(context -> {
                        StatsdMeterRegistry statsdMeterRegistry = context.getBean(StatsdMeterRegistry.class);
                        statsdMeterRegistry.counter("observability.delivery").increment();
                        DatagramPacket datagramPacket = new DatagramPacket(new byte[4096], 4096);
                        datagramSocket.receive(datagramPacket);
                        String packet = new String(datagramPacket.getData(), 0, datagramPacket.getLength(),
                                StandardCharsets.UTF_8);
                        assertThat(packet).contains("observability.delivery:1", "|c", "service:observability-test",
                                "env:test", "version:integration");
                    });
        }
    }

    @Test
    void unavailableAgentDoesNotFailApplicationWork() {
        applicationContextRunner.withPropertyValues("DD_METRICS_ENABLED=true", "DD_AGENT_HOST=127.0.0.1",
                "DD_DOGSTATSD_PORT=9").run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(StatsdMeterRegistry.class).counter("observability.delivery").increment();
                });
    }
}
