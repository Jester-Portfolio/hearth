package io.github.jesterportfolio.hearth.persistence;

/** SQL 원문/접속 주소/인증정보/payload를 오류 메시지에 포함하지 않습니다. */
public final class StorageFailure extends RuntimeException {
    private final String code;
    public StorageFailure(String operation, String code) {
        super(operation + " failed (" + code + ")");
        this.code = code;
    }
    public String code() { return code; }
}
