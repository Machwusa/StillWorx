# StillWorx CRDT Kanban — Android implementation prompt

Implement the Android StillWorx Kanban application described below. This document is the source of truth for the intended prototype behavior and reflects the final Android implementation.

## Goal

Build a local-first, multi-device Kanban board that remains fully usable without a network connection and converges through an Automerge document when the user permits synchronization after reconnecting.

The app must protect a user's current board layout from surprising remote rearrangements. It may reconnect automatically, but after recovering from an offline period it must stage incoming remote documents and wait for an explicit `SYNC` action before applying them to the Room-backed UI.

## Technology

Use:

- Kotlin only
- Jetpack Compose with Material 3
- Hilt for dependency injection
- Coroutines, `Flow`, and immutable UI state
- Room as the application source of truth
- Automerge Java for CRDT state and merging
- OkHttp WebSocket for transport
- Gradle Kotlin DSL

Match the existing Android baseline: minimum SDK 26, compile/target SDK 37, and Java 11 bytecode compatibility. Keep dependency versions in the Gradle version catalog.

Use a single application module. Keep presentation, domain, data, and synchronization responsibilities separate even though they live in one module.

Do not use a hand-written application container or service locator. Use constructor injection throughout. Hilt modules should be limited to infrastructure construction and interface bindings.

## Product behavior

The app contains one board with exactly these columns:

```text
TODO
DOING
DONE
```

Users can:

- create a task; new tasks start in `TODO`
- edit a task title
- delete a task
- drag a task between columns
- reorder a task by dropping it at a position in a column
- perform equivalent moves through accessibility actions

Reject blank or whitespace-only titles.

Use a horizontally scrolling board with approximately 280 dp-wide columns. Cards use keyed lazy-list items and animate insertions, removals, placement changes, and content-size changes so updates received from another device do not jump abruptly.

Use long-press drag and drop with edge auto-scroll. A device must not broadcast transient pointer or drag state. Other devices see only the durable task result after the drop is committed and synchronized.

Avoid arrow buttons as the primary movement control. Drag and drop is the visible interaction; semantic custom actions provide accessible alternatives.

Cards at the bottom of a column must have enough content padding that their elevation and rounded bottom corners are not clipped by the list or parent container.

## Architecture

Use this dependency direction:

```text
Presentation  ->  Domain  <-  Data
                         <-  Sync

Compose UI -> ViewModel -> use cases/repository -> Room
WebSocket -> SyncCoordinator -> Automerge -> Room -> Flow -> UI
```

Room is the only source observed by the UI. Compose and the ViewModel must never read directly from the WebSocket or Automerge document.

The synchronization path and application path meet at Room:

```text
Application path
User gesture -> ViewModel -> use case -> repository -> Room -> Flow -> UI

Synchronization path
WebSocket -> coordinator -> Automerge merge -> projection -> Room -> Flow -> UI
```

Use a `@HiltViewModel` with constructor-injected use cases or repositories. Expose a single immutable `BoardUiState` through `StateFlow`. Combine the Room task stream and synchronization status into this state.

Use Hilt singleton scope for the database, Automerge store, transport, and synchronization coordinator. The coordinator implements the local-change notification and sync-status contracts consumed by the rest of the app.

## Suggested package boundaries

```text
presentation/
  board/
    BoardScreen
    BoardViewModel
    BoardUiState

domain/
  model/
  repository/
  usecase/

data/
  local/
    TaskEntity
    TaskDao
    AppDatabase
  repository/
    TaskRepositoryImpl

sync/
  SyncCoordinator
  SyncTransport
  WebSocketSyncTransport
  CrdtStore
  AutomergeCrdtStore
  SyncModels

di/
  AppModules
```

Exact file names may differ, but preserve these boundaries.

## Room model

Persist tasks with these fields:

```text
id: String             // UUID
title: String
column: String         // TODO, DOING, or DONE
updatedAt: Long        // epoch milliseconds
isDeleted: Boolean     // tombstone
syncPending: Boolean
```

The active-task query excludes tombstones and uses stable ordering:

```sql
ORDER BY updatedAt ASC, id ASC
```

Every local mutation must:

1. update Room first in a transaction
2. assign a fresh `updatedAt`
3. set `syncPending = true`
4. notify the synchronization coordinator after the transaction succeeds

Deletion is a tombstone update, not a physical row deletion.

`syncPending` means that the Room mutation has not yet been folded into the locally persisted Automerge document. Clear it only after updating and saving that document. It is not a server acknowledgement flag.

## CRDT document

Use this shared Automerge schema exactly so Android and iOS can exchange documents:

```text
ROOT
  tasks: Map<taskId, Map>
    id: String
    title: String
    column: String
    updatedAt: Automerge timestamp/Date
    deleted: Boolean
```

Keep `syncPending` in Room only. It is not part of the shared document.

Persist one full Automerge binary document per board in app-private storage:

```text
filesDir/crdt/<boardId>.automerge
```

Save through a temporary file followed by an atomic replacement where supported. Serialize all document access with a coroutine `Mutex`. Preserve full Automerge history; do not flatten the document to JSON and rebuild it.

Automerge decides convergence. Do not add last-write-wins logic based on `updatedAt`. When a field has concurrent values, inspect them with Automerge's conflict API such as `getAll`, log the conflict without sensitive data, and project the deterministic value selected by Automerge. The timestamp is display/order metadata, not a conflict-resolution authority.

## Synchronization coordinator

Implement synchronization as a single-consumer event loop backed by an unlimited `Channel`. All startup, local-change, transport, approval, and shutdown events must pass through that loop so state transitions and Automerge access remain ordered.

### Startup

1. Load or create the persisted Automerge document.
2. Read Room rows with `syncPending = true` and apply them to the document.
3. Persist the document and clear those pending flags transactionally as appropriate.
4. Project the document into Room.
5. Start the WebSocket connection.

### Local change

1. Fetch pending Room rows.
2. Apply them to the Automerge document.
3. Persist the document.
4. Clear the processed `syncPending` flags.
5. If connected and synchronization is not awaiting approval, send the current full document.

When reconnect approval is pending, local edits remain fully available. Fold them into the local persisted Automerge document, but do not send them through the normal local-change path until the user selects `SYNC`.

### Normal remote document

When connected normally and not awaiting approval:

1. Flush pending Room changes into the local Automerge document first.
2. Decode and merge the remote document.
3. Persist the merged document.
4. Project the merged task state into Room in a transaction.

The Room flow then drives an animated UI update.

### Reconnect requiring approval

Track whether the app has connected successfully before and whether it subsequently went offline.

After a connection opens following that offline period:

- set `syncPausedForApproval = true`
- expose that user approval is required
- send the current local document once so peers/server can observe it
- stage incoming document bytes as independent copies in arrival order
- do not merge or project staged documents yet

The initial connection after app startup does not require approval.

When the user selects `SYNC`:

1. Flush pending Room rows into the local Automerge document.
2. Merge all staged documents in arrival order.
3. Clear the staging buffer.
4. Persist the merged document.
5. Project the result into Room once.
6. Clear the paused/approval state.
7. Send the current document if the socket is connected.

This explicit gate prevents remote changes from rearranging the visible board immediately when connectivity returns.

## WebSocket protocol

Send and receive a UTF-8 JSON text envelope containing a Base64-encoded full Automerge document:

```json
{
  "type": "sync",
  "boardId": "demo-board",
  "document": "<base64 full Automerge document>"
}
```

Ignore messages with another `type` or `boardId`. Log and ignore malformed JSON, invalid Base64, and invalid Automerge documents without crashing the app or discarding the current local document.

This prototype sends full documents, not Automerge incremental sync messages.

## Connection and retry behavior

Expose three connection states:

```text
Disconnected
Connecting
Connected
```

Connect automatically on startup. An automatic connection cycle consists of the initial attempt plus up to three automatic retries, with a fixed two-second delay between failures. After those retries are exhausted, stop automatic attempts and expose `manualReconnectRequired = true`.

Selecting `RETRY` performs one manual attempt. If it fails, return directly to the manual-retry state rather than starting another automatic cycle. Reset retry counters after a successful WebSocket open.

Configure the OkHttp client with a 20-second WebSocket ping interval.

Treat an open WebSocket as connected. The protocol has no document acknowledgement, so do not present connection status as confirmation that another device has stored or rendered a document.

## Connection UI

Show a compact connection badge at the top of the board:

- red dot and `Disconnected`
- amber dot and `Connecting...`
- green dot and `Connected`

Animate the three dots in `Connecting...` on an approximately 400 ms cadence. Keep all three dot positions allocated and render inactive dots faintly so the badge width does not change during the animation. Do not show a spinner or a separate connecting banner.

Use compact banners with only enough height for the message and CTA, with an approximate 48 dp minimum height. Banner message text is semibold.

Banner behavior:

- While offline, show a persistent amber banner: `You’re offline. Changes are saved locally.`
- Once automatic retries are exhausted, add a filled, high-contrast, fully rounded `RETRY` CTA.
- On an ordinary successful connection, show a green `Successfully connected.` banner temporarily for about three seconds.
- On a successful reconnection that requires approval, show a green banner: `Successfully reconnected. Sync to apply updates from other devices.` with a filled, high-contrast, fully rounded `SYNC` CTA.
- Keep the reconnect banner visible until the user approves synchronization.
- Show no banner while connecting.

CTA labels are uppercase and must be exactly `RETRY` and `SYNC`.

## Compose quality and accessibility

- Use stable keys for tasks and columns.
- Animate durable remote insertions, removals, and moves without animating transient remote drag gestures.
- Avoid broad recomposition during the connecting-dot animation.
- Preserve badge and banner dimensions through connection-state changes where practical.
- Provide content descriptions for meaningful icons and status dots where the text does not already convey the state.
- Mark column titles as headings.
- Expose custom accessibility actions for moving tasks between columns and positions.
- Meet Material touch-target guidance.
- Support font scaling without clipping CTAs or status text.

## Build-time configuration

Declare the Android configuration keys in `app/build.gradle.kts`:

```text
SYNC_SERVER_URL
SYNC_BOARD_ID
```

Resolve values in this order:

1. matching properties in the root `local.properties`
2. host environment variables with the same names
3. development defaults

Use these development defaults:

```properties
SYNC_SERVER_URL=ws://10.0.2.2:8080
SYNC_BOARD_ID=demo-board
```

Generate them as `BuildConfig` fields and consume `BuildConfig.SYNC_SERVER_URL` and `BuildConfig.SYNC_BOARD_ID` from the dependency-injection layer. Do not hard-code deployment values in UI or repository classes.

For a physical Android device, replace the emulator loopback address with the development computer's LAN IPv4 address, for example:

```properties
SYNC_SERVER_URL=ws://192.168.1.50:8080
SYNC_BOARD_ID=demo-board
```

The phone and computer must be on the same Wi-Fi network, the server must listen on `0.0.0.0`, and the host firewall must allow inbound TCP traffic on the selected port. Rebuild and reinstall the app after changing build-time values.

Do not commit machine-specific `local.properties` values or credentials.

## Required tests

Add focused unit and instrumentation tests for:

### Room

- active queries exclude tombstones
- ordering is deterministic
- local mutations set `syncPending`
- projection updates, inserts, and tombstones correctly

### Automerge store

- create, save, and load a document
- retain history across persistence
- merge divergent offline documents
- inspect concurrent field values while preserving deterministic projection
- recover safely from malformed remote bytes

### Coordinator

- startup flushes pending changes before connecting
- local changes update Room first and then the CRDT
- normal remote changes flush local pending state before merge
- initial connection does not require approval
- reconnect after offline stages remote documents
- local changes remain usable while approval is pending
- `SYNC` merges staged documents and projects once
- retry exhaustion exposes manual retry
- a manual retry failure does not restart the automatic retry loop

### Compose

- the three connection states display the correct dot and label
- connecting has no banner or spinner
- offline and reconnect banners have the correct CTA
- the reconnect banner persists until approval
- drag/drop commits a durable move
- accessibility movement actions work
- large fonts do not clip the compact banners or CTAs

Run the JVM unit tests and relevant connected instrumentation tests. The completed prototype should pass the physical-device suite as well as emulator tests.

## Cross-platform compatibility test

Maintain a binary fixture produced by one platform and loaded by the other. Verify both directions:

1. Android creates a document, iOS loads and edits it, Android reloads the merged binary.
2. iOS creates a document, Android loads and edits it, iOS reloads the merged binary.

Assert the exact schema types, task IDs, tombstones, Unicode titles, timestamps, divergent offline edits, and at least one concurrent field conflict. Do not substitute JSON serialization for this test.

## End-to-end acceptance scenario

1. Connect two devices to the same board.
2. Create tasks on both devices and confirm normal synchronization.
3. Take one device offline and edit, move, create, and delete tasks on both devices.
4. Confirm the offline device remains responsive and displays the persistent amber banner.
5. Allow its automatic retries to exhaust and confirm the `RETRY` CTA appears.
6. Restore connectivity and reconnect.
7. Confirm the visible board is not rearranged by staged remote state.
8. Confirm the green reconnect banner offers `SYNC`.
9. Make another local edit before approving and confirm it is retained.
10. Select `SYNC` and confirm both devices converge without losing either device's changes.
11. Confirm the receiving UI animates durable card changes fluidly.

## Documentation deliverables

Keep a short README that clearly documents:

- where `SYNC_SERVER_URL` and `SYNC_BOARD_ID` are declared
- the `local.properties` and environment-variable resolution order
- emulator and physical-device URL examples
- server binding and firewall requirements for Wi-Fi testing
- build, test, install, and run commands
- architecture and synchronization diagrams
- the reconnect approval behavior
- prototype limitations

Use portable Mermaid syntax in repository documentation. Quote labels containing punctuation and avoid renderer-specific HTML so diagrams work across common GitHub browsers.

## Explicit prototype limitations

Document these limitations rather than hiding them:

- full Automerge documents are transmitted instead of incremental sync messages
- there is no authentication, authorization, encryption layer beyond the chosen WebSocket scheme, or multi-board discovery
- the server is a simple relay/store and the client has no document acknowledgement
- there is no background synchronization guarantee
- retries use a fixed delay with no jitter or exponential backoff
- live pointer and in-progress drag state are not synchronized
- tombstones have no garbage-collection policy
- Automerge convergence does not guarantee a preferred business outcome for concurrent edits

## Definition of done

The work is complete when the app is local-first, uses Hilt, persists both Room and Automerge state, behaves correctly across process restarts, synchronizes two physical devices over Wi-Fi, stages remote state after reconnect until `SYNC`, renders connection and banner states exactly as specified, animates received board updates fluidly, passes the required tests, and documents configuration and architectural tradeoffs clearly.
