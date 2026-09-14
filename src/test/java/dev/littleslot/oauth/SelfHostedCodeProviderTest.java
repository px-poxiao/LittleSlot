package dev.littleslot.oauth;

import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfHostedCodeProviderTest {
    @Test void callbackRequiresAnUnconsumedState() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        SelfHostedCodeProvider provider = new SelfHostedCodeProvider("client", "secret",
                URI.create("https://example.com/oauth/callback"), "127.0.0.1", 0, workers);
        try {
            AuthorizationSession session = provider.start();
            String url = session.verificationUrl.get();
            assertTrue(url.contains("&state="));
            assertEquals(400, get(provider.port(), "/oauth/callback?state=" + repeat('a', 64) + "&code=fake"));
            assertEquals(404, get(provider.port(), "/wrong?state=" + repeat('a', 64) + "&code=fake"));
            session.cancel();
        } finally { provider.close(); workers.shutdownNow(); }
    }

    private static int get(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        try { return connection.getResponseCode(); }
        finally { connection.disconnect(); }
    }

    private static String repeat(char value, int count) {
        char[] text = new char[count]; java.util.Arrays.fill(text, value); return new String(text);
    }
}
