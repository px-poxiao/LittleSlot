package dev.littleslot.oauth;

import com.google.gson.JsonParser;
import dev.littleslot.core.VerifiedAccount;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LittleSkinHttpTest {
    @Test void convertsOnlyUidAndProfileIds() throws Exception {
        VerifiedAccount account = LittleSkinHttp.parseAccount(
                new JsonParser().parse("{\"uid\":42,\"email\":\"private@example.com\"}"),
                new JsonParser().parse("[{\"id\":\"11111111111141118111111111111111\",\"name\":\"Hidden\"}]"), 1000);
        assertEquals(42, account.uid());
        assertEquals("11111111-1111-4111-8111-111111111111", account.profiles().iterator().next().toString());
        assertEquals(1000, account.verifiedAtMillis());
    }

    @Test void rejectsMissingUidAndPartialOrDuplicateLists() {
        assertThrows(IOException.class, () -> LittleSkinHttp.parseAccount(
                new JsonParser().parse("{\"code\":403}"), new JsonParser().parse("[]"), 1000));
        assertThrows(IOException.class, () -> LittleSkinHttp.parseAccount(
                new JsonParser().parse("{\"uid\":42}"), new JsonParser().parse("[{\"name\":\"no id\"}]"), 1000));
        assertThrows(IOException.class, () -> LittleSkinHttp.parseAccount(
                new JsonParser().parse("{\"uid\":42}"),
                new JsonParser().parse("[{\"id\":\"11111111111141118111111111111111\"},{\"id\":\"11111111111141118111111111111111\"}]"), 1000));
    }
}
