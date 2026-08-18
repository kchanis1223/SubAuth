package io.github.kchanis1223.subauth;

public class SubAuthException extends RuntimeException {
    private final String code;

    public SubAuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SubAuthException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
