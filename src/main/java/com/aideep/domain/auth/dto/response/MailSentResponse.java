package com.aideep.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record MailSentResponse(@Schema(example = "true") boolean ok) {
}
