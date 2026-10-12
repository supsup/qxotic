package com.qxotic.jinfer.telemetry;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/**
 * One write of the prompt cache to disk: a {@code save} (append the blocks computed since the last
 * save to the mounted catalog) or an {@code export} (freeze the whole block layer into a new
 * artifact). The duration is the file IO, which the engine runs under its generation lock - a slow
 * save shows up here as the pause every queued request waited through, which is why servers save at
 * shutdown and not per request.
 *
 * <p>Rare by construction (shutdown, an operator's export), so it is on by default.
 */
@Name("jinfer.PromptCacheStore")
@Label("Prompt Cache Store")
@Category({"jinfer", "Memory"})
@Description("One prompt-cache write to disk: an append to the catalog or a full export.")
@StackTrace(false)
@Threshold("0 ms")
public final class PromptCacheStoreEvent extends Event {

    public static final String SAVE = "save";
    public static final String EXPORT = "export";

    /** {@link #SAVE} or {@link #EXPORT}. */
    @Label("Operation")
    public String operation;

    /** The artifact written. */
    @Label("File")
    public String file;

    /**
     * Blocks handed to the writer: the fresh ones since the last save, or the whole tree for an
     * export. A save dedups against what the file already holds, so fewer may land.
     */
    @Label("Blocks")
    public int blocks;

    /** The artifact's size once the write is durable. */
    @Label("File Bytes")
    @DataAmount(DataAmount.BYTES)
    public long fileBytes;
}
