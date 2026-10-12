package com.qxotic.jinfer.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Argument parsing and table/plateau formatting against stubbed measurements; no model, no child
 * process.
 */
class ThreadSweepTest {

    private static final String NL = System.lineSeparator();

    @Test
    void parseSortsAndResolvesAll() {
        assertArrayEquals(new int[] {1, 2, 4, 8}, ThreadSweep.parse("1,2,4,8", 16));
        assertArrayEquals(new int[] {4, 8, 16}, ThreadSweep.parse("16, 8 ,4", 16));
        assertArrayEquals(new int[] {12}, ThreadSweep.parse("all", 12));
        assertArrayEquals(new int[] {1, 2, 12}, ThreadSweep.parse("2,all,1", 12));
    }

    @Test
    void parseDropsDuplicatesIncludingAllNamingAListedCount() {
        assertArrayEquals(new int[] {2, 4}, ThreadSweep.parse("4,2,4,2", 8));
        assertArrayEquals(new int[] {1, 8}, ThreadSweep.parse("1,8,all", 8));
    }

    @Test
    void parseRefusesZeroNegativeAndMalformed() {
        IllegalArgumentException zero =
                assertThrows(IllegalArgumentException.class, () -> ThreadSweep.parse("1,0,2", 8));
        assertTrue(zero.getMessage().contains(">= 1"), zero.getMessage());
        assertTrue(zero.getMessage().contains("0"), zero.getMessage());
        IllegalArgumentException negative =
                assertThrows(IllegalArgumentException.class, () -> ThreadSweep.parse("-4", 8));
        assertTrue(negative.getMessage().contains("-4"), negative.getMessage());
        IllegalArgumentException word =
                assertThrows(IllegalArgumentException.class, () -> ThreadSweep.parse("1,many", 8));
        assertTrue(word.getMessage().contains("'many'"), word.getMessage());
        IllegalArgumentException empty =
                assertThrows(IllegalArgumentException.class, () -> ThreadSweep.parse("1,,2", 8));
        assertTrue(empty.getMessage().contains("empty entry"), empty.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ThreadSweep.parse("", 8));
        assertThrows(IllegalArgumentException.class, () -> ThreadSweep.parse("  ", 8));
        assertThrows(IllegalArgumentException.class, () -> ThreadSweep.parse(null, 8));
        assertThrows(IllegalArgumentException.class, () -> ThreadSweep.parse("1,2", 0));
    }

    @Test
    void labelListsCountsInOrder() {
        assertEquals("1,2,4", ThreadSweep.label(new int[] {1, 2, 4}));
        assertEquals("8", ThreadSweep.label(new int[] {8}));
    }

    @Test
    void parseChildReadsTheSingleRunTable() {
        String stdout =
                """
                jinfer-bench dev | runtime: C2 | jam: none | threads: 4 (requested)
                | model            | threads | test   |              t/s |
                | ---------------- | ------: | ------ | ---------------: |
                | LFM2.5-350M-Q8_0 |       4 | pp512  | 1234.50 \u00b1 12.25 |
                | LFM2.5-350M-Q8_0 |       4 | tg128  |    98.75 \u00b1 0.50 |
                | other-Q4_0       |       4 | tg128@d64 |  40.00 \u00b1 1.00 |
                """;
        Map<String, ThreadSweep.Point> points = ThreadSweep.parseChild(stdout, 4);
        assertEquals(List.of("LFM2.5-350M-Q8_0", "other-Q4_0"), List.copyOf(points.keySet()));
        ThreadSweep.Point lfm = points.get("LFM2.5-350M-Q8_0");
        assertEquals(4, lfm.threads());
        assertEquals(1234.5, lfm.pp(), 1e-9);
        assertEquals(12.25, lfm.ppStddev(), 1e-9);
        assertEquals(98.75, lfm.tg(), 1e-9);
        assertEquals(0.5, lfm.tgStddev(), 1e-9);
        ThreadSweep.Point other = points.get("other-Q4_0");
        assertTrue(Double.isNaN(other.pp()));
        assertEquals(40.0, other.tg(), 1e-9);
    }

    @Test
    void parseChildRefusesARowAtAnotherThreadCount() {
        String row = "| m |       2 | tg128  |    98.75 \u00b1 0.50 |" + NL;
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> ThreadSweep.parseChild(row, 4));
        assertTrue(e.getMessage().contains("threads=4"), e.getMessage());
        assertTrue(e.getMessage().contains("ran 2"), e.getMessage());
        assertTrue(ThreadSweep.parseChild("no table at all" + NL, 4).isEmpty());
    }

    @Test
    void plateauIsTheSmallestCountWithinFivePercentOfMax() {
        List<ThreadSweep.Point> points =
                List.of(
                        point(1, 100, 10),
                        point(2, 190, 18),
                        point(4, 360, 19.5),
                        point(8, 700, 20));
        assertEquals(4, ThreadSweep.plateau(points));
        // exactly on the bound (19 = 0.95 * 20) counts, and the order of the list does not matter
        assertEquals(
                2, ThreadSweep.plateau(List.of(point(4, 1, 20), point(2, 1, 19), point(1, 1, 10))));
        // a fall-off at high counts does not move the plateau
        List<ThreadSweep.Point> fallOff =
                List.of(point(1, 1, 10), point(2, 1, 20), point(4, 1, 20), point(8, 1, 15));
        assertEquals(2, ThreadSweep.plateau(fallOff));
        assertEquals(-1, ThreadSweep.plateau(List.of(point(1, 1, Double.NaN))));
    }

    @Test
    void tableListsSpeedupsAgainstTheSmallestCountAndEndsWithThePlateau() {
        List<ThreadSweep.Point> points =
                List.of(
                        new ThreadSweep.Point(8, 700, 7, 20, 0.2),
                        new ThreadSweep.Point(1, 100, 1, 10, 0.1),
                        new ThreadSweep.Point(2, 190, 1.9, 18, 0.18),
                        new ThreadSweep.Point(4, 360, 3.6, 19.5, 0.195));
        String expected =
                """
                | model | threads |           pp t/s |           tg t/s | tg speedup | pp speedup |
                | ----- | ------: | ---------------: | ---------------: | ---------: | ---------: |
                | m     |       1 |    100.00 \u00b1 1.00 |     10.00 \u00b1 0.10 |      1.00x |      1.00x |
                | m     |       2 |    190.00 \u00b1 1.90 |     18.00 \u00b1 0.18 |      1.80x |      1.90x |
                | m     |       4 |    360.00 \u00b1 3.60 |     19.50 \u00b1 0.20 |      1.95x |      3.60x |
                | m     |       8 |    700.00 \u00b1 7.00 |     20.00 \u00b1 0.20 |      2.00x |      7.00x |
                plateau: tg within 5% of max from 4 threads
                """;
        assertEquals(expected.replace("\n", NL), ThreadSweep.table("m", points));
    }

    @Test
    void tableWidensTheModelColumnAndMarksSkippedTests() {
        List<ThreadSweep.Point> points =
                List.of(
                        new ThreadSweep.Point(1, 100, 1, Double.NaN, Double.NaN),
                        new ThreadSweep.Point(2, 150, 1, Double.NaN, Double.NaN));
        String expected =
                """
                | model                  | threads |           pp t/s |           tg t/s | tg speedup | pp speedup |
                | ---------------------- | ------: | ---------------: | ---------------: | ---------: | ---------: |
                | model-with-a-long-name |       1 |    100.00 \u00b1 1.00 |              n/a |        n/a |      1.00x |
                | model-with-a-long-name |       2 |    150.00 \u00b1 1.00 |              n/a |        n/a |      1.50x |
                plateau: n/a (tg not measured)
                """;
        assertEquals(
                expected.replace("\n", NL), ThreadSweep.table("model-with-a-long-name", points));
    }

    private static ThreadSweep.Point point(int threads, double pp, double tg) {
        return new ThreadSweep.Point(threads, pp, 0, tg, 0);
    }
}
