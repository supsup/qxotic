package com.qxotic.jinfer.telemetry;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/**
 * One prompt-cache lookup: the request's positions against what the cache could serve. {@link
 * PromptCacheEvent} samples the cache's health once a second; this is the per-request answer to
 * "how much of THIS prompt was reused, from where, and how long did selecting and restoring it
 * take". The duration covers the whole lookup - picking a retained session, matching the block tree
 * and restoring the matched chain - and stops before the uncached suffix is prefilled, so it
 * measures the cache, not the model.
 *
 * <p>{@code matchedPrefixTokens} is bounded by the one-short law: an identical prompt reports its
 * length minus one, never its length, because the final position always re-ingests so the logits
 * are fresh. {@code resumedFromDisk} is true when the restored chain touched at least one block
 * mounted from a catalog file - a cold process serving warm answers is exactly what the persisted
 * cache promises, and this is where that promise becomes observable.
 *
 * <p>One event per served request, so it stays on by default. The off cost is one short-lived
 * allocation and a field check (the JDK idiom: the event object is what answers {@code
 * isEnabled()}), per request, not per token.
 */
@Name("jinfer.PromptCacheLookup")
@Label("Prompt Cache Lookup")
@Category({"jinfer", "Memory"})
@Description(
        "One prompt-cache lookup: positions requested, positions reused, the tier that served"
                + " them, and whether the restore came from a mounted catalog.")
@StackTrace(false)
@Threshold("0 ms")
public final class PromptCacheLookupEvent extends Event {

    /** Positions in the request, prompt batches flattened (media rows count one each). */
    @Label("Requested Tokens")
    public int requestedTokens;

    /** Positions served without prefill: the retained state's length or the restored chain. */
    @Label("Matched Prefix Tokens")
    public int matchedPrefixTokens;

    /** {@code session}, {@code blocks} or {@code fresh}, the cache's own tier names. */
    @Label("Tier")
    public String tier;

    /** Whether the restored chain included a block mounted from a catalog file. */
    @Label("Resumed From Disk")
    public boolean resumedFromDisk;

    /** Blocks in the tree at lookup time (RAM and mounted); 0 without a block layer. */
    @Label("Tree Nodes")
    public int treeNodes;
}
