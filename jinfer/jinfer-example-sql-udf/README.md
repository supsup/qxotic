# The model as a SQL function

[![Java 25+](https://img.shields.io/badge/Java-25%2B-007396?logo=java&logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-green.svg?logo=apache)](../LICENSE)

Jinfer runs the model inside the JVM.
H2 runs the database inside the JVM.
So a query can call the model the way it calls `UPPER`: a static Java method, bound with `CREATE ALIAS`, invoked once per row with no sidecar, no socket, and no serialization of the row.

```sql
SELECT id, LLM_CLASSIFY(body, 'bug,feature,question') FROM tickets
```

This example registers five functions over one lazily loaded engine and runs three queries against fifty support tickets.

| Function                             | Returns      | How                                                                     |
| ------------------------------------ | ------------ | ----------------------------------------------------------------------- |
| `LLM_COMPLETE(prompt)`               | `VARCHAR`    | greedy completion, token-budgeted                                       |
| `LLM_CLASSIFY(text, 'a,b,c')`        | `VARCHAR`    | exactly one of the labels, enforced by a grammar at the sampler         |
| `LLM_EMBED(text)`                    | `REAL ARRAY` | the embedding model's vector                                            |
| `COSINE(a, b)`                       | `DOUBLE`     | cosine similarity of two arrays, plain arithmetic                       |
| `LLM_JUDGE(text, question)`          | `BOOLEAN`    | `LLM_CLASSIFY` over `yes,no`, so the verdict is a boolean, not a string |

## What it shows

**A closed label set is a grammar, not a parse.**
`LLM_CLASSIFY` hands the engine `root ::= "bug" | "feature" | "question"` through `ChatEngine.Request.grammar`, the same GBNF path the judge-advisor example uses for its JSON verdict.
The sampler masks every token that cannot continue one of the literals, so free text is unrepresentable and the result needs no `LIKE '%bug%'` cleanup.
Decoding is greedy (temperature 0), so a label is a deterministic function of its prompt.

**The instruction is paid once per query, not once per row.**
The system turn of a classify or judge call depends only on the label set (or the question), never on the row.
The backend defines it into the engine's prompt cache (`ChatEngine.definePrompt`) the first time it sees it; every row's prompt then starts with a prefix the block layer already holds, and only the row's text is ingested.
The timing line prints how many prompt tokens the cache restored.

**Limitation.**
Each row is still its own decode pass.
Jinfer's engine serializes generations on one lock and exposes no public multi-sequence (tree-attention) decode, so N rows cost N short passes over a shared prefix rather than one batched pass.
That is the honest shape of "many questions from one prefix" today; the cache removes the prefill, not the per-row decode.
`LLM_EMBED` has the same shape: H2 calls it once per row, so each call is a one-text `embedAll` and the embedder's greedy packer (many texts per forward pass, the local-rag example's path) goes unused.

## Run

From the repository root:

```bash
make -C jinfer example-sql-udf
```

which is

```bash
mvn -Pexamples -pl jinfer/jinfer-example-sql-udf -am install -DskipTests -Djam.native.skip=true
mvn -Pexamples -pl jinfer/jinfer-example-sql-udf exec:exec
```

The `examples` profile is required: the module is listed only there.
`-Djam.native.skip=true` skips the CMake build of `jam-native`, which rides in through `jam-vector`'s test scope; the demo runs on `jam-vector` (pure Java).

Models download on first use into the Jinfer cache and load from there afterwards.
Knobs, all system properties (`SQLUDF_MODEL=` and `SQLUDF_SHUFFLE=` on the make target set the first and the last):

| Property                         | Default                                      |
| -------------------------------- | -------------------------------------------- |
| `jinfer.sqludf.model`            | `LiquidAI/LFM2.5-350M-GGUF:Q8_0`             |
| `jinfer.sqludf.embedModel`       | `LiquidAI/LFM2.5-Embedding-350M-GGUF:Q8_0`   |
| `jinfer.sqludf.contextCapacity`  | `2048`                                       |
| `jinfer.sqludf.maxTokens`        | `128` (the `LLM_COMPLETE` budget)            |
| `jinfer.sqludf.shuffleLabels`    | `false`                                      |

## The three queries

Classify, counted by SQL:

```sql
SELECT label, COUNT(*) AS n
FROM (SELECT LLM_CLASSIFY(body, 'bug,feature,question') AS label FROM tickets)
GROUP BY label ORDER BY n DESC, label
```

Embed into a column, then nearest neighbours of one ticket:

```sql
ALTER TABLE tickets ADD COLUMN embedding REAL ARRAY;
UPDATE tickets SET embedding = LLM_EMBED(body);
SELECT id, body, COSINE(embedding, (SELECT embedding FROM tickets WHERE id = 7)) AS sim
FROM tickets WHERE id <> 7 ORDER BY sim DESC LIMIT 3
```

Judge in a `WHERE` clause:

```sql
SELECT id, body FROM tickets WHERE LLM_JUDGE(body, 'Is the customer asking for a refund?') ORDER BY id
```

Expected output shape (labels and neighbours depend on the model):

```text
>>> loaded 50 tickets into H2 (in-memory), aliases registered
>>> models loaded in N.Ns (chat + embedding, outside every query clock)

>>> SELECT ... LLM_CLASSIFY(body, 'bug,feature,question') ... GROUP BY label
>>> classify: 50 rows in N.Ns (N.N rows/s); 50 calls, prompt N tokens (N restored from cache), pp N tok/s, tg N tok/s
    bug         NN
    feature     NN
    question    NN
    #1   bug        The app crashes every time I open the settings page on...
    ...

>>> UPDATE tickets SET embedding = LLM_EMBED(body)
>>> embed: 50 rows in N.Ns (N.N rows/s)
>>> nearest neighbours of #7: I cancelled my plan yesterday but was still billed today...
    #4   0.NNN  I was charged twice for my subscription this month. Please...
    ...

>>> SELECT id, body FROM tickets WHERE LLM_JUDGE(body, 'Is the customer asking for a refund?')
    #4   I was charged twice for my subscription this month. Please...
    ...
>>> judge: 50 rows in N.Ns (N.N rows/s); 50 calls, prompt N tokens (N restored from cache), pp N tok/s, tg N tok/s
```

Tickets 4, 7, 20, 30, 40 and 50 are the rows the author wrote as refund requests; compare the judge's rows against that intent.
The model-free test pins the fake backend to `[4, 20, 40, 50]` because the fake matches the word "refund", which tickets 7 and 30 do not contain.

## Timings

<!-- placeholder: paste the three timing lines from a real run, with the machine and the JVM -->

| Query    | Rows | Wall  | Rows/s | Prompt tokens (restored) | pp tok/s | tg tok/s |
| -------- | ---- | ----- | ------ | ------------------------ | -------- | -------- |
| classify | 50   | TBD   | TBD    | TBD                      | TBD      | TBD      |
| embed    | 50   | TBD   | TBD    | n/a                      | n/a      | n/a      |
| judge    | 50   | TBD   | TBD    | TBD                      | TBD      | TBD      |

The table is filled in from a real run of `make -C jinfer example-sql-udf`, with the machine and the JVM named next to it.

## Bias caveat: decision models have favourite answers

A small model asked to pick from a list is commonly reported not to pick uniformly: asked to roll a fair die it tends to roll a loaded one, and asked to classify it may lean on the first label, the shortest, or the one its training data favoured.
This is a claim to test, not a fact to assume, and the test is cheap here: run the `GROUP BY` query twice with the labels in two orders and compare the two distributions.
Constrained decoding makes the answer well-formed, not well-founded: the grammar guarantees the reply is in the set, and nothing more.

Two mitigations, one implemented here:

1. **Label-order shuffling** (`-Djinfer.sqludf.shuffleLabels=true`): the labels are listed in an order derived from the row's text, deterministic per row and uniform over rows, so a positional preference cannot favour one label across a whole table.
   The grammar is order-independent.
   The cost is one cached instruction prefix per permutation instead of one per label set (six for three labels), all of which the block layer holds after the first pass.
2. **Two-pass voting** (not implemented, sketched): classify each row under two label orders and keep the label only when both passes agree, otherwise return `NULL` or a third tie-break pass.
   It doubles the decode cost and is the honest answer when the first pass disagrees with itself under reordering.

Neither turns a 350M model into a calibrated classifier.
Check the distribution of a `GROUP BY` against what you know about the data before trusting a label column.

## Tests

- Unit tests: `mvn -Pexamples -pl jinfer/jinfer-example-sql-udf -am test -Djam.native.skip=true`.
  They register the aliases on an in-memory H2 and dispatch through a fake `LlmBackend`: no model, no network.
- Model-backed test: `SqlUdfIT`, tagged `integration`, finds the two GGUFs through the repository's `TestModels` lookup and skips when they are not cached:
  `mvn -Pexamples -pl jinfer/jinfer-example-sql-udf test -Dsurefire.excludedGroups= -Dgroups=integration`.

## Design notes

- The H2 dependency belongs to this module only; the engine jars stay dependency-free.
- `LlmBackend` is the seam: three methods, no engine types.
  `JinferBackend` is the only class that imports `ChatEngine`.
- A `NULL` argument returns `NULL` without a model call, as SQL functions do.
- `LLM_EMBED` frames every row with the embedding model's document prefix (`""` when the card prescribes none), one convention for both sides of a row-to-row cosine.
