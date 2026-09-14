package dev.littleslot.oauth;

import java.io.IOException;

public final class OAuthException extends IOException {
    private final String code;
    public OAuthException(String code) {
        super("OAuth failed: " + code);
        this.code = code;
    }
    public String code() { return code; }
}
