package com.aideep.domain.meeting.dto.request;

import com.aideep.domain.meeting.entity.Bottype;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.URL;

import java.util.UUID;

public record InviteBotRequest(
        @NotNull
        @URL(protocol = "https")
        String url,

        @NotNull
        Bottype type,

        @NotNull
        UUID workspaceId,

        @NotNull
        UUID nodeId
) {
}
