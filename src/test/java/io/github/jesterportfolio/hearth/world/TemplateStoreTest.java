package io.github.jesterportfolio.hearth.world;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** File safety tests with synthetic data; no server or mocked lifecycle assertions. */
public final class TemplateStoreTest {
    @Test void preservesIdentityAndOtherWorldsOnCopyAndRejectedInputs() throws Exception {
        Path root = Files.createTempDirectory("hearth-template-test-");
        Path source = Files.createDirectory(root.resolve("source"));
        Path a = Files.createDirectory(root.resolve("a"));
        Path b = Files.createDirectory(root.resolve("b"));
        Files.createDirectory(source.resolve("region"));
        Files.writeString(source.resolve("region/r.0.0.mca"), "synthetic-region");
        Path identity = Path.of("data/paper/metadata.dat");
        for (Path dir : new Path[] {source, a, b}) Files.createDirectories(dir.resolve(identity).getParent());
        Files.writeString(source.resolve(identity), "template-identity");
        Files.writeString(a.resolve(identity), "identity-a");
        Files.writeString(b.resolve(identity), "identity-b");
        var store = new TemplateStore();
        String original = store.digest(source);
        store.copySpatial(source, a, original);
        store.copySpatial(source, b, original);
        require(Files.readString(a.resolve(identity)).equals("identity-a")
                && Files.readString(b.resolve(identity)).equals("identity-b"), "identity preserved");
        Files.writeString(a.resolve("region/r.0.0.mca"), "changed-a");
        require(store.digest(source).equals(original) && store.digest(b).equals(original), "physical copy isolation");
        expectIo(() -> store.copySpatial(source, b, "wrong-checksum"));
        expectIo(() -> store.copySpatial(source, source, original));
        Path bad = Files.createDirectory(root.resolve("bad"));
        Files.createSymbolicLink(bad.resolve("region"), source.resolve("region"));
        expectIo(() -> store.copySpatial(source, bad, original));
        Path maliciousSource = Files.createDirectory(root.resolve("linked-source"));
        Files.createSymbolicLink(maliciousSource.resolve("region"), source.resolve("region"));
        expectIo(() -> store.copySpatial(maliciousSource, b, original));
        expectIo(() -> store.copySpatial(root.resolve("missing"), b, original));
        require(store.digest(source).equals(original) && store.digest(b).equals(original), "failure preserves source/other copy");
        System.out.println("TemplateStoreTest PASS: copies, independent identity/data, checksum, overlap, symlink and missing-source rejection");
        // Disposable test data are left in /tmp; no recursive deletion path exists in this utility.
    }
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    private static void expectIo(Throwing call) throws Exception {
        try { call.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }
    private interface Throwing { void run() throws Exception; }
}
