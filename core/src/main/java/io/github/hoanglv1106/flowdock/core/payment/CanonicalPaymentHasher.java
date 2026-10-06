package io.github.hoanglv1106.flowdock.core.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Canonical business payload hashing per contracts.md section 2 (RFC 8785 JCS + SHA-256).
 *
 * <p>Scope and rules, all traceable to RFC 8785:
 * <ul>
 *   <li>Property order: {@code amount} &lt; {@code currency} &lt; {@code transactionId}. The keys
 *       are ASCII, where RFC 8785 section 3.2.3 UTF-16 code unit order equals code point order.</li>
 *   <li>No whitespace between tokens (section 3.2.1).</li>
 *   <li>String escaping per section 3.2.2.2: control characters U+0000..U+001F use the short
 *       backslash escapes for backspace, tab, line feed, form feed and carriage return where
 *       defined, and lowercase hex {@code u} escapes otherwise. Backslash and double quote are
 *       escaped; every other code point is emitted "as is". Lone surrogates terminate with an
 *       error, as section 3.2.2.2 requires. (Written without literal backslash-u text: Java
 *       processes Unicode escapes before parsing, including inside comments.)</li>
 *   <li>Numbers per section 3.2.2.3. Amounts are integers within the approved safe range, so the
 *       ECMA-262 7.1.12.1 serialization is the plain base-10 form with no exponent or fraction.
 *       This is the same range Appendix B note 1 recommends for values interpreted as true
 *       integers.</li>
 *   <li>Output encoded as UTF-8 (section 3.2.4).</li>
 * </ul>
 *
 * <p>{@link PaymentPayload} rejects null and missing fields before hashing, which is the ingress
 * order contracts.md section 2.3 mandates. JCS itself would not strip nulls.
 */
public final class CanonicalPaymentHasher {
    private static final char[] LOWERCASE_HEX = "0123456789abcdef".toCharArray();

    private CanonicalPaymentHasher() {
    }

    public static String canonicalJson(PaymentPayload payload) {
        return "{\"amount\":" + payload.amount()
                + ",\"currency\":" + canonicalString(payload.currency())
                + ",\"transactionId\":" + canonicalString(payload.transactionId())
                + "}";
    }

    /** Canonical UTF-8 bytes, the exact input to the SHA-256 digest. */
    public static byte[] canonicalBytes(PaymentPayload payload) {
        return canonicalJson(payload).getBytes(StandardCharsets.UTF_8);
    }

    /** Returns the 64-character lowercase hexadecimal SHA-256 digest of the canonical UTF-8 bytes. */
    public static String hash(PaymentPayload payload) {
        return toLowercaseHex(sha256(canonicalBytes(payload)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String toLowercaseHex(byte[] digest) {
        char[] hex = new char[digest.length * 2];
        for (int index = 0; index < digest.length; index++) {
            int value = digest[index] & 0xFF;
            hex[index * 2] = LOWERCASE_HEX[value >>> 4];
            hex[index * 2 + 1] = LOWERCASE_HEX[value & 0x0F];
        }
        return new String(hex);
    }

    /** RFC 8785 section 3.2.2.2 string serialization. */
    private static String canonicalString(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\t' -> builder.append("\\t");
                case '\n' -> builder.append("\\n");
                case '\f' -> builder.append("\\f");
                case '\r' -> builder.append("\\r");
                default -> {
                    if (character < 0x20) {
                        builder.append("\\u");
                        builder.append(LOWERCASE_HEX[(character >>> 12) & 0x0F]);
                        builder.append(LOWERCASE_HEX[(character >>> 8) & 0x0F]);
                        builder.append(LOWERCASE_HEX[(character >>> 4) & 0x0F]);
                        builder.append(LOWERCASE_HEX[character & 0x0F]);
                    } else if (Character.isSurrogate(character)) {
                        // Well-formed pairs must be emitted as the code point they encode;
                        // a lone surrogate MUST abort per section 3.2.2.2.
                        if (!Character.isHighSurrogate(character)
                                || index + 1 >= value.length()
                                || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException(
                                    "lone surrogate U+" + String.format("%04X", (int) character)
                                            + " must terminate JCS serialization");
                        }
                        builder.append(character).append(value.charAt(++index));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }
}
