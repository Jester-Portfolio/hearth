package io.github.jesterportfolio.hearth.lifecycle;

import java.util.concurrent.*;

public final class LifecycleFailure extends RuntimeException {
    private final String code;
    public LifecycleFailure(String code) { super(code); this.code=code; }
    public String code() { return code; }
    public static String code(Throwable error) {
        while (error instanceof CompletionException || error instanceof ExecutionException) error=error.getCause();
        return error instanceof LifecycleFailure f ? f.code() : error instanceof io.github.jesterportfolio.hearth.persistence.StorageFailure f ? f.code() : error.getClass().getSimpleName();
    }
}
