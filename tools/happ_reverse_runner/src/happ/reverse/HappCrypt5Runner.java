package happ.reverse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import su.happ.proxyutility.util.ErrorCodeJNIWrapper;

public final class HappCrypt5Runner {
    private HappCrypt5Runner() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("ERR missing crypt5 input");
            return;
        }

        String payload = stripPrefix(args[0]);
        String stepQ = swapSix(payload);
        String nativeOut = new ErrorCodeJNIWrapper().c(stepQ);
        String stepO = swapPairs(nativeOut);
        String decoded = decodeBase64(stepO);

        System.out.println("PAYLOAD_LEN=" + payload.length());
        System.out.println("STEP_Q_LEN=" + stepQ.length());
        System.out.println("NATIVE_LEN=" + nativeOut.length());
        System.out.println("STEP_O_LEN=" + stepO.length());
        System.out.println("DECODED_LEN=" + decoded.length());
        System.out.println("DECODED_BEGIN");
        System.out.println(decoded);
        System.out.println("DECODED_END");
    }

    private static String stripPrefix(String input) {
        String value = input.trim();
        int fragmentIndex = value.indexOf('#');
        if (fragmentIndex >= 0) {
            value = value.substring(0, fragmentIndex);
        }
        String prefix = "happ://crypt5/";
        if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return value.substring(prefix.length());
        }
        return value;
    }

    private static String swapSix(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index += 6) {
            String chunk = value.substring(index, Math.min(index + 6, value.length()));
            if (chunk.length() > 5) {
                out.append(chunk.charAt(1));
                out.append(chunk.charAt(3));
                out.append(chunk.charAt(5));
                out.append(chunk.charAt(0));
                out.append(chunk.charAt(2));
                out.append(chunk.charAt(4));
            } else {
                out.append(chunk);
            }
        }
        return out.toString();
    }

    private static String swapPairs(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index += 2) {
            String chunk = value.substring(index, Math.min(index + 2, value.length()));
            if (chunk.length() > 1) {
                out.append(chunk.charAt(1));
                out.append(chunk.charAt(0));
            } else {
                out.append(chunk);
            }
        }
        return out.toString();
    }

    private static String decodeBase64(String value) {
        String normalized = value.trim();
        int padding = normalized.length() % 4;
        if (padding != 0) {
            normalized = normalized + "====".substring(padding);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ignored) {
            decoded = Base64.getUrlDecoder().decode(normalized);
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
