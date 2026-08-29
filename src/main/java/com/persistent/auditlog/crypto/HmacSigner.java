package com.persistent.auditlog.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HMAC-SHA256 signing for export bundles, so a recipient can confirm a bundle
 * truly originated from this service (not just that it is internally
 * self-consistent). See docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md.
 */
@Component
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretKeyBytes;

    public HmacSigner(@Value("${audit.security.export-signing-key}") String secret) {
        this.secretKeyBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKeyBytes, ALGORITHM));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC signature", e);
        }
    }

    public boolean verify(String data, String signatureHex) {
        if (signatureHex == null) {
            return false;
        }
        String expected = sign(data);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            signatureHex.getBytes(StandardCharsets.UTF_8));
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }
}
