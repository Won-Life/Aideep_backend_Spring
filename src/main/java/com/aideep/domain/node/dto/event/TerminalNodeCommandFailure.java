package com.aideep.domain.node.dto.event;

import tools.jackson.databind.JsonNode;

/** A failure already durably recorded, including an exhausted retryable failure. */
public record TerminalNodeCommandFailure(JsonNode error) {
}
