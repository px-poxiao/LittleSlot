package dev.littleslot.oauth;

public interface OAuthProvider extends AutoCloseable {
    AuthorizationSession start();
    /** Non-billable probe. Must never create an authorization request. */
    boolean available();
    @Override default void close() { }
}
