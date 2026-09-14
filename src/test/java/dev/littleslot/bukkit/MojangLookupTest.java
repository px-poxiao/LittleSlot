package dev.littleslot.bukkit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MojangLookupTest {
    @Test void validProfileIsMatchedWhile404IsTheOnlyMissingResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lookup/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring("/lookup/".length());
            int status = "jeb_".equals(name) || "malformed".equals(name) ? 200 : "missing".equals(name) ? 404 : 503;
            byte[] body = status == 200
                    ? ("malformed".equals(name) ? "{\"name\":\"malformed\",\"id\":\"invalid\"}"
                    : "{\"name\":\"Jeb_\",\"id\":\"853c80ef3c3749fdaa49938b674adae6\"}").getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            MojangLookup lookup = new MojangLookup(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/lookup/"), 1000);
            assertEquals(UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6"), lookup.byName("jeb_"));
            assertNull(lookup.byName("missing"));
            assertThrows(MojangLookup.LookupFailure.class, () -> lookup.byName("broken"));
            assertThrows(MojangLookup.LookupFailure.class, () -> lookup.byName("malformed"));
        } finally { server.stop(0); }
    }

    @Test void timeoutIsDistinguishedFromOtherFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lookup/", exchange -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            MojangLookup lookup = new MojangLookup(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/lookup/"), 100);
            assertThrows(SocketTimeoutException.class, () -> lookup.byName("jeb_"));
        } finally { server.stop(0); }
    }
}
