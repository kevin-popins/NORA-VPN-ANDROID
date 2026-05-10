package su.happ.proxyutility.util;

import java.nio.charset.StandardCharsets;

public final class ErrorCodeJNIWrapper {
    static {
        System.load("/data/local/tmp/happ-runner/liberror-code.so");
    }

    private native byte[] jniGetErrorMessageFromString2(String errorString);

    public String c(String value) {
        return new String(jniGetErrorMessageFromString2(value), StandardCharsets.UTF_8);
    }
}
