package com.jobradar.notifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signed, expiring links to a stored CV that work from any device through the public dashboard URL.
 * A link is {@code key}, an expiry time and an HMAC-SHA256 over both; the bucket stays private.
 * The secret is {@code jobradar.dashboard.link-secret}; with no secret no link is ever valid.
 */
@Component
public class CvLinkSigner {

    private final byte[] secret;
    private final Clock clock;

    CvLinkSigner(@Value("${jobradar.dashboard.link-secret:}") String secret, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public boolean enabled() {
        return secret.length >= 16;
    }

    /** @return {@code expiryEpochSecond.signature}, to be sent with the key */
    public String sign(String key, Duration ttl) {
        long expiry = clock.instant().plus(ttl).getEpochSecond();
        return expiry + "." + mac(key + "|" + expiry);
    }

    /** True when the token was signed for exactly this key, has not expired and the secret is set. */
    public boolean verify(String key, String token) {
        if (!enabled() || key == null || token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot < 1) {
            return false;
        }
        try {
            long expiry = Long.parseLong(token.substring(0, dot));
            if (expiry < clock.instant().getEpochSecond()) {
                return false;
            }
            // Constant-time comparison, so the signature cannot be guessed byte by byte.
            return MessageDigest.isEqual(
                    mac(key + "|" + expiry).getBytes(StandardCharsets.UTF_8),
                    token.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String mac(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
