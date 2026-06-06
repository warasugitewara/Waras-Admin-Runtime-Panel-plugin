package dev.warasugi.warp.auth;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;

public class TotpManager {
    private final String secret;
    private final DefaultCodeVerifier verifier;

    public TotpManager(String secret) {
        this.secret = secret;
        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1);
        this.verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        this.verifier.setTimePeriod(30);
        this.verifier.setAllowedTimePeriodDiscrepancy(1);
    }

    public static String generateSecret() {
        return new DefaultSecretGenerator().generate();
    }

    public boolean verify(String code) {
        return verifier.isValidCode(secret, code);
    }

    public String getQrUri(String issuer) {
        return "otpauth://totp/" + issuer + "?secret=" + secret + "&issuer=" + issuer + "&algorithm=SHA1&digits=6&period=30";
    }

    public String getSecret() {
        return secret;
    }
}
