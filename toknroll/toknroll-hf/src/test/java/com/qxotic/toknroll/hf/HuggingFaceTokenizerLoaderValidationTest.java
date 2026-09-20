package com.qxotic.toknroll.hf;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qxotic.toknroll.ByteLevel;
import com.qxotic.toknroll.Tokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HuggingFaceTokenizerLoaderValidationTest {

    @TempDir Path tempDir;

    /** Loader failures arrive wrapped, so match against the whole cause chain. */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append(" | ");
        }
        return sb.toString();
    }

    @Test
    void fromLocalRejectsNonExistentPath() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HuggingFaceTokenizerLoader.fromLocal(Path.of("/nonexistent/path")));
    }

    @Test
    void fromLocalRejectsDirectoryWithoutTokenizerJson() {
        assertThrows(RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(tempDir));
    }

    @Test
    void fromLocalRejectsNullPath() {
        assertThrows(NullPointerException.class, () -> HuggingFaceTokenizerLoader.fromLocal(null));
    }

    @Test
    void fromHuggingFaceRejectsBlankUser() {
        assertThrows(
                RuntimeException.class,
                () -> HuggingFaceTokenizerLoader.fromHuggingFace("", "repo"));
        assertThrows(
                RuntimeException.class,
                () -> HuggingFaceTokenizerLoader.fromHuggingFace(null, "repo"));
    }

    @Test
    void fromHuggingFaceRejectsBlankRepo() {
        assertThrows(
                RuntimeException.class,
                () -> HuggingFaceTokenizerLoader.fromHuggingFace("user", ""));
    }

    // --- token-id bounds (issue #3) ---------------------------------------------------------
    //
    // In the HuggingFace format token ids are VALUES, so one entry decides an allocation. The
    // GGUF path is structurally immune because there ids are list positions. These pin the
    // refusal on BOTH id-bearing paths, and the second one is not the path the issue reported.

    @Test
    void oversizedVocabTokenIdIsRejected() throws Exception {
        // The 60-byte file from issue #3: one entry, an id near Integer.MAX_VALUE. Before the
        // bound this allocated String[2147483001] and exhausted the heap.
        String json = "{\"model\":{\"type\":\"BPE\",\"vocab\":{\"a\":2147483000},\"merges\":[]}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        assertThrows(RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
    }

    @Test
    void maxIntVocabTokenIdIsRejected() throws Exception {
        // Integer.MAX_VALUE is the sharper case: maxId + 1 overflows to a NEGATIVE array size, so
        // without the bound this is a NegativeArraySizeException rather than an OOM.
        String json = "{\"model\":{\"type\":\"BPE\",\"vocab\":{\"a\":2147483647},\"merges\":[]}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        assertThrows(RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
    }

    @Test
    void negativeVocabTokenIdIsRejected() throws Exception {
        String json = "{\"model\":{\"type\":\"BPE\",\"vocab\":{\"a\":-1},\"merges\":[]}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        // Assert the MESSAGE, not just the type. Unpatched, tokens[-1] throws
        // ArrayIndexOutOfBoundsException -- also a RuntimeException -- so a type-only assertion
        // would pass without the fix and prove nothing.
        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
        assertTrue(
                describe(thrown).contains("must be non-negative"),
                () -> "expected the non-negative refusal, got: " + describe(thrown));
    }

    @Test
    void oversizedAddedTokenIdIsRejected() throws Exception {
        // NOT the path issue #3 reported. added_tokens[].id drives an Arrays.copyOf with the same
        // unbounded shape, reachable from an otherwise well-formed vocabulary, and it sits in a
        // loop so the cost can be paid repeatedly within one file.
        String json =
                "{\"model\":{\"type\":\"BPE\",\"vocab\":{\"a\":0,\"b\":1},\"merges\":[]},"
                        + "\"added_tokens\":[{\"id\":2147483000,\"content\":\"<x>\"}]}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        assertThrows(RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
    }

    @Test
    void negativeAddedTokenIdIsRejected() throws Exception {
        // Without the check this is an ArrayIndexOutOfBoundsException from tokens[id].
        String json =
                "{\"model\":{\"type\":\"BPE\",\"vocab\":{\"a\":0,\"b\":1},\"merges\":[]},"
                        + "\"added_tokens\":[{\"id\":-5,\"content\":\"<x>\"}]}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
        assertTrue(
                describe(thrown).contains("must be non-negative"),
                () -> "expected the non-negative refusal, got: " + describe(thrown));
    }

    @Test
    void sparseButLegitimateIdSpaceStillLoads() throws Exception {
        // POSITIVE CONTROL for the bound. Vocabulary padding for tensor alignment leaves real gaps
        // in the id space, so a check that only ever refuses would be worse than the bug it fixes.
        // A full byte-level vocab (256 ids) plus a token parked well above it MUST still load.
        // If the bound is ever tightened past what real files do, this is what fails.
        String vocab =
                HuggingFaceTokenizerTestFixtures.buildByteLevelVocab(Map.of("<pad>", 1500));
        String json =
                "{\"model\":" + HuggingFaceTokenizerTestFixtures.buildBpeModel(vocab, "[]", "") + "}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        assertNotNull(HuggingFaceTokenizerLoader.fromLocal(file));
    }

    @Test
    void emptyVocabIsRejected() throws Exception {
        String json = "{\"model\":{\"type\":\"BPE\",\"vocab\":{},\"merges\":[]}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        assertThrows(RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
    }

    @Test
    void nonBpeModelTypeIsRejected() throws Exception {
        String json = "{\"model\":{\"type\":\"Unigram\",\"vocab\":{\"h\":0},\"merges\":[]}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        assertThrows(RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
    }

    @Test
    void missingModelFieldIsRejected() throws Exception {
        String json = "{\"normalizer\":{\"type\":\"NFC\"}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        assertThrows(RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
    }

    @Test
    void nonObjectRootIsRejected() throws Exception {
        String json = "[]";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        assertThrows(RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
    }

    @Test
    void nonStringVocabValueIsRejected() throws Exception {
        String json =
                "{\"model\":{\"type\":\"BPE\",\"vocab\":{\"h\":\"not-a-number\"},\"merges\":[]}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        assertThrows(RuntimeException.class, () -> HuggingFaceTokenizerLoader.fromLocal(file));
    }

    private static String byteLevelVocabJson() {
        StringBuilder vocab = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            if (i > 0) vocab.append(",");
            vocab.append(jsonEscape(ByteLevel.encode(new byte[] {(byte) i}))).append(":").append(i);
        }
        return vocab.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') sb.append('\\').append(c);
            else if (c >= 0x20 && c < 0x7F) sb.append(c);
            else sb.append("\\u").append(String.format("%04x", (int) c));
        }
        return sb.append("\"").toString();
    }

    @Test
    void byteLevelTokenizerJsonLoads() throws Exception {
        String json =
                "{\"model\":{"
                        + "\"type\":\"BPE\","
                        + "\"vocab\":{"
                        + byteLevelVocabJson()
                        + "},"
                        + "\"merges\":[]"
                        + "}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        Tokenizer t = HuggingFaceTokenizerLoader.fromLocal(file);
        assertNotNull(t);
    }

    @Test
    void normalizerNfcLoads() throws Exception {
        String json =
                "{"
                        + "\"normalizer\":{\"type\":\"NFC\"},"
                        + "\"model\":{"
                        + "\"type\":\"BPE\","
                        + "\"vocab\":{"
                        + byteLevelVocabJson()
                        + "},"
                        + "\"merges\":[]"
                        + "}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        Tokenizer t = HuggingFaceTokenizerLoader.fromLocal(file);
        assertNotNull(t);
    }

    @Test
    void normalizerLowercaseLoads() throws Exception {
        String json =
                "{"
                        + "\"normalizer\":{\"type\":\"Lowercase\"},"
                        + "\"model\":{"
                        + "\"type\":\"BPE\","
                        + "\"vocab\":{"
                        + byteLevelVocabJson()
                        + "},"
                        + "\"merges\":[]"
                        + "}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json);
        Tokenizer t = HuggingFaceTokenizerLoader.fromLocal(file);
        assertNotNull(t);
    }
}
