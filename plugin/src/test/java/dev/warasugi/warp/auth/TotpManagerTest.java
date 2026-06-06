package dev.warasugi.warp.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TotpManagerTest {

    @Test
    void generateSecret_returnsNonBlankString() {
        String secret = TotpManager.generateSecret();
        assertFalse(secret.isBlank());
        assertTrue(secret.length() >= 16);
    }

    @Test
    void verify_withInvalidCode_returnsFalse() {
        TotpManager m = new TotpManager(TotpManager.generateSecret());
        assertFalse(m.verify("000000"));
    }

    @Test
    void getQrUri_containsSecretAndIssuer() {
        String secret = TotpManager.generateSecret();
        TotpManager m = new TotpManager(secret);
        String uri = m.getQrUri("WARP");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains(secret));
        assertTrue(uri.contains("WARP"));
    }

    @Test
    void getSecret_returnsConstructorValue() {
        String secret = TotpManager.generateSecret();
        assertEquals(secret, new TotpManager(secret).getSecret());
    }
}
