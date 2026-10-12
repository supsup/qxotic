/**
 * The model as a SQL function. H2 runs in the JVM, Jinfer runs in the JVM, so {@code CREATE ALIAS
 * LLM_CLASSIFY FOR "...LlmFunctions.classify"} binds the model to a query with no sidecar and no
 * IPC: a row's text goes into the static method as a String and the label comes back as one.
 *
 * <p>Shape: {@link com.qxotic.jinfer.example.sqludf.LlmBackend} is the narrow seam the UDFs call
 * (fakeable in tests), {@link com.qxotic.jinfer.example.sqludf.JinferBackend} is the engine behind
 * it, {@link com.qxotic.jinfer.example.sqludf.Llm} holds the one lazily loaded instance per JVM,
 * and {@link com.qxotic.jinfer.example.sqludf.LlmFunctions} is what H2 binds.
 */
package com.qxotic.jinfer.example.sqludf;
