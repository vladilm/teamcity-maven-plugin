package org.jetbrains.teamcity.incremental;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

final class DigestUtil {
    private DigestUtil() {
    }

    static String sha256(String value) {
        MessageDigest digest = newSha256();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return toHex(digest.digest());
    }

    static String sha256(List<String> values) {
        MessageDigest digest = newSha256();
        int i;
        for (i = 0; i < values.size(); i++) {
            digest.update(values.get(i).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return toHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        int i;
        for (i = 0; i < bytes.length; i++) {
            int unsignedByte = bytes[i] & 0xff;
            hex[i * 2] = alphabet[unsignedByte >>> 4];
            hex[i * 2 + 1] = alphabet[unsignedByte & 0x0f];
        }
        return new String(hex);
    }
}
