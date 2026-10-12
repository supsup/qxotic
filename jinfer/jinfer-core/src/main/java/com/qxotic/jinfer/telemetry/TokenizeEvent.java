package com.qxotic.jinfer.telemetry;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/**
 * One prompt encoded to token ids - the chat template rendered and tokenized for a request. {@code
 * chars} over the event's duration is tokenizer throughput; {@code tokens} is what the prompt cost
 * the context. A slow first token that is neither prefill nor model load shows up here.
 *
 * <p>Per PROMPT, never per token: the decode loop's per-token {@code decodeBytes} is on the hot
 * path, and an event there would perturb the latency {@link DecodeEvent} measures.
 */
@Name("jinfer.Tokenize")
@Label("Tokenize")
@Category({"jinfer", "Inference"})
@Description("One prompt encoded to token ids; chars over duration is tokenizer throughput.")
@StackTrace(false)
@Threshold("0 ms")
public final class TokenizeEvent extends Event {

    /** Text characters in the conversation; media parts count nothing. */
    @Label("Characters")
    public int chars;

    /** Prompt positions the encoding produced, media positions included. */
    @Label("Tokens")
    public int tokens;
}
