package com.aideep.domain.meeting.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RecallProperties.class, MeetingEventProperties.class})
public class RecallConfiguration {
}
