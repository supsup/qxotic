package com.qxotic.jinfer.example.sqludf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** UDF registration and dispatch on an in-memory H2 over the fake backend: no GGUF, no network. */
class LlmFunctionsTest {

    private final FakeBackend fake = new FakeBackend();
    private Connection db;

    @BeforeEach
    void open() throws SQLException {
        Llm.install(fake);
        // an anonymous in-memory database: private to this connection, gone when it closes
        db = DriverManager.getConnection("jdbc:h2:mem:");
        LlmFunctions.register(db);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
        Llm.install(null);
        System.clearProperty(Llm.SHUFFLE_LABELS_PROPERTY);
    }

    private ResultSet query(String sql) throws SQLException {
        Statement statement = db.createStatement();
        ResultSet rs = statement.executeQuery(sql);
        assertTrue(rs.next(), "no row for " + sql);
        return rs;
    }

    @Test
    void classifyDispatchesTheLabelsAndTheInstruction() throws SQLException {
        ResultSet rs =
                query("SELECT LLM_CLASSIFY('The app crashes on start', 'bug,feature,question')");
        assertEquals("bug", rs.getString(1));
        assertEquals(1, fake.choices.size());
        FakeBackend.Choice choice = fake.choices.get(0);
        assertEquals(List.of("bug", "feature", "question"), choice.labels());
        assertEquals("The app crashes on start", choice.text());
        assertTrue(choice.instruction().contains("bug, feature, question"), choice.instruction());
    }

    @Test
    void labelsAreTrimmedAndDeduplicated() throws SQLException {
        ResultSet rs = query("SELECT LLM_CLASSIFY('x', ' a , b,a ,c')");
        assertEquals("a", rs.getString(1));
        assertEquals(List.of("a", "b", "c"), fake.choices.get(0).labels());
    }

    @Test
    void aSingleOrBlankLabelIsRefused() {
        assertThrows(SQLException.class, () -> query("SELECT LLM_CLASSIFY('x', 'only')"));
        assertThrows(SQLException.class, () -> query("SELECT LLM_CLASSIFY('x', 'a,,b')"));
        assertTrue(fake.choices.isEmpty(), "a refused label set must not reach the model");
    }

    @Test
    void judgeIsABooleanOverYesNo() throws SQLException {
        ResultSet rs =
                query(
                        "SELECT LLM_JUDGE('I want a refund', 'Is the customer asking for a"
                                + " refund?'), LLM_JUDGE('Add dark mode', 'Is the customer asking"
                                + " for a refund?')");
        assertTrue(rs.getBoolean(1));
        assertFalse(rs.getBoolean(2));
        assertEquals(List.of("yes", "no"), fake.choices.get(0).labels());
        assertTrue(
                fake.choices.get(0).instruction().contains("Is the customer asking for a refund?"));
    }

    @Test
    void embedAndCosineRoundTripThroughAnArrayColumn() throws SQLException {
        try (Statement ddl = db.createStatement()) {
            ddl.execute("CREATE TABLE t (id INT, v REAL ARRAY)");
            ddl.execute("INSERT INTO t VALUES (1, LLM_EMBED('banana')), (2, LLM_EMBED('eerie'))");
        }
        ResultSet rs =
                query(
                        "SELECT COSINE(v, LLM_EMBED('banana')), CARDINALITY(v) FROM t WHERE id ="
                                + " 1");
        assertEquals(1.0, rs.getDouble(1), 1e-6);
        assertEquals(3, rs.getInt(2));
        ResultSet other = query("SELECT COSINE(v, LLM_EMBED('banana')) FROM t WHERE id = 2");
        assertTrue(other.getDouble(1) < 0.999, "different texts, different vectors");
        // how many times H2 evaluates the constant-argument call is its planner's business
        assertTrue(fake.embedded.containsAll(List.of("banana", "eerie")), fake.embedded.toString());
    }

    @Test
    void cosineRefusesMismatchedLengths() {
        assertThrows(
                SQLException.class,
                () -> query("SELECT COSINE(ARRAY[1.0, 0.0], ARRAY[1.0, 0.0, 0.0])"));
    }

    @Test
    void completeCarriesTheTokenBudget() throws SQLException {
        ResultSet rs = query("SELECT LLM_COMPLETE('hi')");
        assertEquals("echo: hi", rs.getString(1));
        assertEquals(LlmFunctions.DEFAULT_COMPLETE_MAX_TOKENS, fake.lastMaxTokens);
    }

    @Test
    void nullInNullOutWithoutAModelCall() throws SQLException {
        ResultSet rs =
                query(
                        "SELECT LLM_CLASSIFY(NULL, 'a,b'), LLM_CLASSIFY('x', NULL),"
                                + " LLM_JUDGE(NULL, 'q'), LLM_EMBED(NULL), LLM_COMPLETE(NULL),"
                                + " COSINE(NULL, ARRAY[1.0])");
        for (int column = 1; column <= 6; column++) {
            assertNull(rs.getObject(column), "column " + column);
        }
        assertTrue(fake.choices.isEmpty() && fake.embedded.isEmpty() && fake.completed.isEmpty());
    }

    @Test
    void shuffledLabelsAreAPermutationDeterministicPerRow() throws SQLException {
        System.setProperty(Llm.SHUFFLE_LABELS_PROPERTY, "true");
        query("SELECT LLM_CLASSIFY('some text about nothing', 'a,b,c,d')");
        query("SELECT LLM_CLASSIFY('some text about nothing', 'a,b,c,d')");
        query("SELECT LLM_CLASSIFY('a different row entirely', 'a,b,c,d')");
        List<String> first = fake.choices.get(0).labels();
        assertEquals(Set.of("a", "b", "c", "d"), Set.copyOf(first));
        assertEquals(first, fake.choices.get(1).labels(), "same row, same order");
        // the instruction lists the labels in the shuffled order: the grammar and the prompt agree
        assertTrue(
                fake.choices.get(0).instruction().contains(String.join(", ", first)),
                fake.choices.get(0).instruction());
        // another row: still the whole set, in whatever order its text hashes to
        assertEquals(Set.of("a", "b", "c", "d"), Set.copyOf(fake.choices.get(2).labels()));
    }

    @Test
    void theDemoRunsEndToEndOnTheFake() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Main.Report report;
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.US_ASCII)) {
            report = Main.run(out);
        }
        String printed = buffer.toString(StandardCharsets.US_ASCII);
        assertEquals(50, report.counts().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(
                Set.of("bug", "feature", "question").containsAll(report.counts().keySet()),
                report.counts().toString());
        assertEquals(3, report.nearest().size());
        assertFalse(report.nearest().contains(Main.PROBE_ID));
        // the fake says yes exactly when the body mentions a refund
        assertEquals(List.of(4, 20, 40, 50), report.refunds());
        assertTrue(printed.contains(">>> classify: 50 rows"), printed);
        assertTrue(printed.contains(">>> embed: 50 rows"), printed);
        assertTrue(printed.contains(">>> judge: 50 rows"), printed);
        assertEquals(50, fake.embedded.size());
    }
}
