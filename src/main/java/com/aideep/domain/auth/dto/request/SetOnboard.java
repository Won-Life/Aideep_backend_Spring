package com.aideep.domain.auth.dto.request;

import com.aideep.domain.onboarding.entity.MeetingPlatform;
import com.aideep.domain.onboarding.entity.UsagePurpose;
import jakarta.annotation.Nullable;

import java.util.List;

public record SetOnboard(
        @Nullable
        String userName,

        UsagePurpose usageProposal,

        List<MeetingPlatform> meeting
) {
}
