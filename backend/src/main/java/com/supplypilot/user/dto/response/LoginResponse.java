package com.supplypilot.user.dto.response;

public record LoginResponse(
        String accessToken,
        String tokenType,
        AuthenticatedUserResponse user
) {
    public static LoginResponse of(String accessToken, AuthenticatedUserResponse user) {
        return new LoginResponse(accessToken, "Bearer", user);
    }
}
