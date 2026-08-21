# Queued multi-file download protocol plan

Status: implementation plan for `t3code/download-queue-perf-fixes`
Baseline: `661e6294` (`gen/release-hardening`)
Date: 2026-08-21

## Finding

The current transfer path has a high-impact round-trip floor for packs with
many small files:

1. `DownloadManager` starts at most five download tasks.
2. Each `DownloadClient.downloadFile(...)` acquires one pooled `Connection`.
3. `Connection.sendDownloadFile(...)` sends one `FILE_REQUEST` and then reads
   that file's complete header, data frames, and `END_OF_TRANSMISSION` frame on
   its single-thread executor.
4. The connection is returned to the pool only after the complete response.

The pool reuses TLS connections and removes repeated handshakes, but it does
not remove the per-file request/response barrier. For `N` host-served files,
the current lower bound is approximately `ceil(N / 5)` serialized batches of
request latency, in addition to transfer time. This is high for high-latency
routes and packs containing many small files. It is less important for a few
large files where disk, compression, or transport throughput dominates.

The current response format cannot safely be pipelined as-is: data frames have
no file identifier, and an error closes the connection. A protocol change must
therefore add item identity and bounded per-item failure handling.

The existing frame scratch buffers, heap chunk stream, and direct codec paths
already address avoidable copy/allocation costs. Do not change compression,
chunk-size, camouflage, or transport defaults without representative
benchmarks; those are not confirmed high/critical findings in this audit.

### Code anchors

- `loader/core/src/main/java/pl/skidam/automodpack_loader_core/utils/DownloadManager.java:37-41`
  caps the download executor at five tasks, and `:387-400` waits for one
  `downloadFile` future per task.
- `core/src/main/java/pl/skidam/automodpack_core/protocol/DownloadClient.java:328-401`
  leases one connection per operation and returns it only from the operation's
  completion callback.
- `core/src/main/java/pl/skidam/automodpack_core/protocol/DownloadClient.java:464-527`
  gives each connection one worker and performs one request followed by one
  complete response read.
- `core/src/main/java/pl/skidam/automodpack_core/protocol/DownloadClient.java:549-583`
  consumes the response header, all data frames, and EOT before completion.
- `core/src/main/java/pl/skidam/automodpack_core/protocol/netty/handler/ServerMessageHandler.java:77-132`
  handles one legacy file request and emits a response stream without an item
  identifier; `:150-157` closes the channel after an error.
- `core/src/main/java/pl/skidam/automodpack_core/protocol/NetUtils.java:39-58`
  defines the current v1 message types and frame-size limits.

## Goals

- Send a bounded queue of file keys after one connection has been acquired.
- Remove one request/response round trip per file while preserving streaming.
- Keep memory bounded: do not assemble an entire pack or batch of file bytes.
- Report success or failure for each item independently.
- Preserve authentication, canonical key validation, exact-size checks, CAS
  staging, progress callbacks, cancellation, and retry semantics.
- Keep old clients and servers interoperable through explicit negotiation.

## Non-goals

- Do not increase the connection count to hide the latency floor.
- Do not multiplex file bytes out of order until a measured use case requires it.
- Do not add unauthenticated masking, speculative compression, or unbounded
  queues.
- Do not batch third-party HTTP downloads; this protocol only covers the
  authenticated AutoModpack host path.

## Recommended wire design

Add a new negotiated transfer capability/version. Leave v1 message meanings
unchanged. A v1 peer continues to use one `FILE_REQUEST` per connection
operation.

### Batch request

Use a new request type with a bounded payload:

```text
version
batch-request-type
secret
item-count
  repeated item:
    item-id
    key-length
    canonical file key bytes
```

The implementation must enforce all of the following before allocating or
starting a transfer:

- a maximum item count;
- a maximum total request payload;
- a maximum key length;
- valid, canonical SHA-1 or supported catalogue key syntax;
- a non-zero item ID unique within the batch.

The secret is authenticated once for the batch. It must not be trusted merely
because a previous item on the channel was valid.

### Batch response

Responses are emitted in request order, one item at a time. Each item begins
with a control frame containing:

```text
version
item-response-type
item-id
status
expected-file-size (when status is success)
error-length + error bytes (when status is failure)
```

For a successful item, the existing compressed data-frame format carries the
file bytes, followed by an item EOT containing the same `item-id`. For a
failed item, the response contains only the bounded error record and then the
next item can begin; the channel does not close solely because one key is
missing.

The item ID is required even if responses are currently ordered. It makes the
interface robust to future scheduling changes and lets the client reject a
mis-correlated stream instead of writing bytes to the wrong destination.

## Client module and seam

Introduce one deep transfer module behind a small interface, for example:

```java
CompletionStage<List<DownloadResult>> downloadBatch(List<DownloadRequest> requests);
```

`DownloadRequest` owns the item ID, key, destination, and progress callback.
`DownloadResult` owns the item outcome and error category. Callers should not
know frame ordering, response parsing, connection leasing, or retry cleanup.

The `DownloadClient` remains the connection-pool adapter. A connection should
allow only a bounded number of queued batches or items, and should apply
backpressure before accepting more request bytes. The implementation should:

- serialize one batch request and flush it once;
- read item headers and data sequentially;
- open each local destination only after a valid success header;
- write exactly the declared number of bytes;
- require matching version and item ID on EOT;
- complete each item as soon as its EOT is verified;
- close the affected connection on framing, size, ID, or authentication
  violations;
- cancel by closing the active connection and completing unresolved items as
  cancelled, so a later retry cannot reuse a contaminated stream.

This seam has leverage because `DownloadManager`, historical-catalogue
loading, and future bulk consumers can share the same batching and failure
rules. The protocol codec and Netty handler remain adapters behind it rather
than exposing wire details to update orchestration.

## Server adapter and backpressure

Add a per-channel transfer queue in the server handler. On a batch request:

1. Authenticate and validate the entire request envelope.
2. Resolve every key and capture immutable path/size metadata before sending
   the first response, subject to the batch limits.
3. Enqueue item descriptors, not file bytes.
4. Stream exactly one item at a time through the existing chunked path.
5. Emit that item's EOT, then advance the queue.

Do not call multiple chunked streams concurrently on one channel. The queue
must be bounded, and a full queue must produce a protocol error before more
work is accepted. File channels must close on success, failure, cancellation,
and disconnect. A disconnect must release all queued descriptors without
reading or buffering their contents.

Legacy v1 requests must retain their current response contract until the new
capability is negotiated. In particular, do not reinterpret the old
`FILE_RESPONSE_TYPE` or make old clients parse item IDs they do not know.

## DownloadManager integration

The manager already has a five-task scheduler, so the integration should batch
only the host-backed tasks that are eligible for the AutoModpack client:

- collect a bounded group of internal-host items before submitting a batch;
- leave third-party HTTP items on their existing path;
- map each item to its existing temporary CAS destination;
- promote and verify each successful item independently;
- retry only failed items, not the whole batch;
- preserve aggregate byte progress and per-file callbacks;
- drain and cancel the batch before `cancelAllAndShutdown()` returns.

The first version should preserve request order. A later measured optimization
may use multiple bounded batches per connection, but it must not let a large
file head-of-line block all small files without evidence that out-of-order
streaming is worth the added buffering and scheduling complexity.

## Validation before implementation

Add a deterministic protocol harness before changing production code. It must
be able to hold the first response and observe whether all requested keys have
already arrived. The current v1 path should demonstrate one request per
operation; the new path should demonstrate one batch request for the chosen
items.

Required tests:

- one batch with zero, one, and the maximum allowed item count;
- mixed small and multi-chunk files;
- missing key among successful items;
- malformed count, key length, item ID, frame length, and EOT;
- duplicate IDs and response IDs in the wrong order;
- per-item destination-open failure;
- disconnect during a header, body, and queued item;
- cancellation with no reuse of the contaminated connection;
- v1 client/server compatibility and explicit v2 capability negotiation;
- authentication failure before any file bytes are emitted.

Run a latency benchmark with a fixed artificial RTT/delay and the same file
manifest for v1 and v2. Record request frames, completed batches, wall time,
bytes transferred, peak queued items, and allocation/GC samples. The success
criterion is a material reduction in wall time for many small files without a
regression in large-file throughput or bounded-memory behavior.

## Implementation order

1. Add protocol constants, bounded message records, and codec tests.
2. Add capability/version negotiation while retaining the v1 path.
3. Add server validation and a single-item-at-a-time transfer queue.
4. Add the client batch seam and item-level response handling.
5. Integrate eligible host tasks in `DownloadManager` with per-item retries.
6. Run focused protocol tests, the latency benchmark, full core/loader tests,
   and one bridge-enabled download scenario for each supported transport.

Do not publish a throughput claim until the benchmark has measured both the
current five-connection v1 path and the queued path on representative packs.
