package dev.littleslot.oauth;

import dev.littleslot.core.VerifiedAccount;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public final class AuthorizationSession {
    public final CompletableFuture<String> verificationUrl = new CompletableFuture<String>();
    public final CompletableFuture<VerifiedAccount> result = new CompletableFuture<VerifiedAccount>();
    private volatile Future<?> task;

    void task(Future<?> task) { this.task = task; }
    public void cancel() {
        Future<?> current = task;
        if (current != null) current.cancel(true);
        result.cancel(true);
    }
}
