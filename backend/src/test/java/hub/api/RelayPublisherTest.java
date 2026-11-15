package hub.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelayPublisherTest {

    @Test
    void signMatchesRfc4231TestCase2() throws Exception {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                RelayPublisher.sign("Jefe", "what do ya want for nothing?"));
    }

    @Test
    void signIsLowercaseHexOfUtf8Body() throws Exception {
        // HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog"), the well-known Wikipedia example
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                RelayPublisher.sign("key", "The quick brown fox jumps over the lazy dog"));
        assertEquals(64, RelayPublisher.sign("s", "{\"q\":{\"btc\":[1,2,3]}}").length());
    }
}
