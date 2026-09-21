package com.aideep.domain.auth.dto;

public record SignupTicket(String provider, String providerUserId, String email, String displayName) {
}
