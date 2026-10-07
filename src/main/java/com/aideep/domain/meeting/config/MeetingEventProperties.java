package com.aideep.domain.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("meeting.events")
public record MeetingEventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("onnode:ai:meeting-events:v1") String streamKey
) {
    public MeetingEventProperties {
        if (streamKey == null || streamKey.isBlank()) {
            throw new IllegalArgumentException("streamKey must not be blank");
        }
    }
}
