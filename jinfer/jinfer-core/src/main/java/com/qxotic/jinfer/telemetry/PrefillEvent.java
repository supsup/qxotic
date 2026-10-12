package com.qxotic.jinfer.telemetry;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/**
 * One prompt chunk ingested - a prefill batch of up to the state's batch capacity. {@code tokens}
 * over the event's duration is prompt-processing throughput for that chunk, the number {@code
 * pp512} reports in aggregate; a recording of a long prompt shows whether the chunks slow down as
 * the context fills, which the per-request {@link InferenceEvent#prefillTime} cannot.
 *
 * <p>One event per CHUNK rather than per prompt: the chunk is the unit the kernels see. Low
 * frequency - a 4096-token prompt is eight events at the default width - so it stays on by
 * default, unlike {@link DecodeEvent}.
 */
@Name("jinfer.Prefill")
@Label("Prefill")
@Category({"jinfer", "Inference"})
@Description("One prompt chunk ingested; tokens over duration is prompt-processing throughput.")
@StackTrace(false)
@Threshold("0 ms")
public final class PrefillEvent extends Event {

    /** Positions this chunk advanced the context by. */
    @Label("Tokens")
    public int tokens;
}
