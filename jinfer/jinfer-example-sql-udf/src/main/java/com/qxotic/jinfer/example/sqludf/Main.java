package com.qxotic.jinfer.example.sqludf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The demo: an in-memory H2 database, the five aliases, fifty support tickets from the bundled CSV,
 * and three queries that call the model from SQL - classify with a GROUP BY, embed then nearest
 * neighbours by COSINE, judge in a WHERE clause. Each query prints its rows, its wall time and what
 * the engine spent (prompt tokens, how many the cache restored, pp/tg rates).
 *
 * <p>Run: {@code make -C jinfer example-sql-udf}, or {@code mvn -Pexamples -pl
 * jinfer/jinfer-example-sql-udf exec:exec} after an install of the reactor.
 */
public final class Main {

    static final String LABELS = "bug,feature,question";
    static final String QUESTION = "Is the customer asking for a refund?";

    /** The ticket whose nearest neighbours the embedding query finds. */
    static final int PROBE_ID = 7;

    static final String CLASSIFY_COUNTS =
            "SELECT label, COUNT(*) AS n FROM"
                    + " (SELECT LLM_CLASSIFY(body, ?) AS label FROM tickets)"
                    + " GROUP BY label ORDER BY n DESC, label";
    static final String CLASSIFY_SAMPLE =
            "SELECT id, body, LLM_CLASSIFY(body, ?) AS label FROM tickets WHERE id <= 5"
                    + " ORDER BY id";
    static final String EMBED_ALL = "UPDATE tickets SET embedding = LLM_EMBED(body)";
    static final String NEAREST =
            "SELECT id, body,"
                    + " COSINE(embedding, (SELECT embedding FROM tickets WHERE id = ?)) AS sim"
                    + " FROM tickets WHERE id <> ? ORDER BY sim DESC LIMIT 3";
    static final String JUDGE = "SELECT id, body FROM tickets WHERE LLM_JUDGE(body, ?) ORDER BY id";

    /** What the three queries found; the test asserts on it, the demo prints it. */
    record Report(Map<String, Integer> counts, List<Integer> nearest, List<Integer> refunds) {}

    private Main() {}

    public static void main(String[] args) throws Exception {
        run(System.out);
    }

    static Report run(PrintStream out) throws SQLException, IOException {
        try (Connection db = DriverManager.getConnection("jdbc:h2:mem:tickets")) {
            LlmFunctions.register(db);
            int rows = loadTickets(db);
            out.printf(">>> loaded %d tickets into H2 (in-memory), aliases registered%n", rows);

            // the models map on first use; pay that here, on its own line, so every rows/s below
            // is model work only (the first classify row still pays the one-time prefix define,
            // which is why a query's restored-token count covers N-1 rows, not N)
            long loadStarted = System.nanoTime();
            Llm.backend().load();
            out.printf(
                    ">>> models loaded in %.1fs (chat + embedding, outside every query clock)%n",
                    (System.nanoTime() - loadStarted) / 1e9);

            // 1. classify + GROUP BY: one grammar-pinned label per row, counted by SQL
            out.printf("%n>>> SELECT ... LLM_CLASSIFY(body, '%s') ... GROUP BY label%n", LABELS);
            Map<String, Integer> counts = new LinkedHashMap<>();
            Timing classify = Timing.start();
            try (PreparedStatement query = db.prepareStatement(CLASSIFY_COUNTS)) {
                query.setString(1, LABELS);
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) counts.put(rs.getString(1), rs.getInt(2));
                }
            }
            classify.report(out, "classify", rows);
            counts.forEach((label, n) -> out.printf("    %-10s %3d%n", label, n));
            try (PreparedStatement query = db.prepareStatement(CLASSIFY_SAMPLE)) {
                query.setString(1, LABELS);
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        out.printf(
                                "    #%-3d %-10s %s%n",
                                rs.getInt(1), rs.getString(3), preview(rs.getString(2)));
                    }
                }
            }

            // 2. embed every row into a REAL ARRAY column, then nearest neighbours by COSINE
            out.printf("%n>>> %s%n", EMBED_ALL);
            try (Statement ddl = db.createStatement()) {
                ddl.execute("ALTER TABLE tickets ADD COLUMN embedding REAL ARRAY");
            }
            Timing embed = Timing.start();
            try (Statement update = db.createStatement()) {
                update.executeUpdate(EMBED_ALL);
            }
            embed.report(out, "embed", rows);
            out.printf(
                    ">>> nearest neighbours of #%d: %s%n", PROBE_ID, preview(body(db, PROBE_ID)));
            List<Integer> nearest = new ArrayList<>();
            try (PreparedStatement query = db.prepareStatement(NEAREST)) {
                query.setInt(1, PROBE_ID);
                query.setInt(2, PROBE_ID);
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        nearest.add(rs.getInt(1));
                        out.printf(
                                "    #%-3d %.3f  %s%n",
                                rs.getInt(1), rs.getDouble(3), preview(rs.getString(2)));
                    }
                }
            }

            // 3. judge in a WHERE clause: a boolean the planner can filter on
            out.printf(
                    "%n>>> SELECT id, body FROM tickets WHERE LLM_JUDGE(body, '%s')%n", QUESTION);
            List<Integer> refunds = new ArrayList<>();
            Timing judge = Timing.start();
            try (PreparedStatement query = db.prepareStatement(JUDGE)) {
                query.setString(1, QUESTION);
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        refunds.add(rs.getInt(1));
                        out.printf("    #%-3d %s%n", rs.getInt(1), preview(rs.getString(2)));
                    }
                }
            }
            judge.report(out, "judge", rows);
            return new Report(counts, nearest, refunds);
        }
    }

    /**
     * The bundled tickets into {@code tickets(id, body)}. The CSV is deliberately trivial - a
     * header, then {@code id,body} with no commas or quotes in a body - so the loader is a split.
     */
    static int loadTickets(Connection db) throws SQLException, IOException {
        try (Statement ddl = db.createStatement()) {
            ddl.execute("CREATE TABLE tickets (id INT PRIMARY KEY, body VARCHAR NOT NULL)");
        }
        int rows = 0;
        try (PreparedStatement insert =
                        db.prepareStatement("INSERT INTO tickets (id, body) VALUES (?, ?)");
                BufferedReader in = openTickets()) {
            String line = in.readLine(); // header
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                int comma = line.indexOf(',');
                if (comma < 0) throw new IOException("malformed ticket line: " + line);
                insert.setInt(1, Integer.parseInt(line.substring(0, comma).strip()));
                insert.setString(2, line.substring(comma + 1).strip());
                insert.executeUpdate();
                rows++;
            }
        }
        return rows;
    }

    private static BufferedReader openTickets() throws IOException {
        InputStream in = Main.class.getResourceAsStream("/tickets.csv");
        if (in == null) throw new IOException("tickets.csv is not on the classpath");
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
    }

    private static String body(Connection db, int id) throws SQLException {
        try (PreparedStatement query =
                db.prepareStatement("SELECT body FROM tickets WHERE id = ?")) {
            query.setInt(1, id);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private static String preview(String text) {
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }

    /** Wall time of a query plus the engine's own accounting over the calls it made. */
    private record Timing(long startedNanos, LlmBackend backend) {

        static Timing start() {
            LlmBackend backend = Llm.backend();
            backend.resetStats();
            return new Timing(System.nanoTime(), backend);
        }

        void report(PrintStream out, String name, int rows) {
            double seconds = (System.nanoTime() - startedNanos) / 1e9;
            LlmBackend.Stats stats = backend.stats();
            out.printf(
                    ">>> %s: %d rows in %.1fs (%.1f rows/s)", name, rows, seconds, rows / seconds);
            if (stats.calls() > 0) {
                out.printf(
                        "; %d calls, prompt %d tokens (%d restored from cache), pp %.0f tok/s,"
                                + " tg %.0f tok/s",
                        stats.calls(),
                        stats.promptTokens(),
                        stats.restoredTokens(),
                        stats.promptTokensPerSecond(),
                        stats.completionTokensPerSecond());
            }
            out.println();
        }
    }
}
