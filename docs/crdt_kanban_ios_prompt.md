# StillWorx CRDT Kanban — iOS implementation prompt

Implement an iOS counterpart to the StillWorx Android Kanban application. Match the Android product behavior and wire protocol exactly so the two clients can synchronize the same Automerge document over the existing server.

## Goal

Build a local-first, multi-device Kanban board that remains fully usable without a network connection and converges through Automerge when the user permits synchronization after reconnecting.

Automatic connectivity checks must remain in place. However, after returning from an offline period, incoming documents must be staged until the user explicitly selects `SYNC`. This protects the visible board from being rearranged unexpectedly while the user is working.

## Technology

Use:

- Swift and SwiftUI
- Swift Concurrency
- SwiftData as the application source of truth
- Observation for view state
- `automerge-swift` for CRDT state and merging
- `URLSessionWebSocketTask` for transport
- Swift Package Manager

Use explicit constructor injection from an application composition root. Keep protocols at architectural boundaries and inject concrete infrastructure there. Do not use a global service locator or hide mutable dependencies in SwiftUI environment values.

Prefer actors for mutable synchronization state. Pass immutable, `Sendable` snapshots across actor boundaries. Isolate Automerge document access in one actor and SwiftData access behind a main-actor or model-actor gateway appropriate to the deployment target.

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

Present a horizontally scrolling board with columns sized appropriately for a phone. Use native SwiftUI drag/drop APIs and edge auto-scroll. Cards must have stable identity and animate insertions, removals, position changes, and content-size changes so updates received from another device feel fluid.

Do not synchronize transient pointer or drag state. Another device sees only the durable result after the drop is committed and synchronized.

Avoid arrow buttons as the primary movement control. Drag and drop is the visible interaction; accessibility actions provide equivalent movement.

Ensure the final card in every column has enough bottom content padding that its shadow and rounded corners are not clipped.

## Architecture

Use this dependency direction:

```text
Presentation  ->  Domain  <-  Data
                         <-  Sync

SwiftUI -> board model -> use cases/repository -> SwiftData
WebSocket -> sync coordinator -> Automerge -> SwiftData -> observation -> UI
```

SwiftData is the only source observed by the UI. Views and the board model must never read directly from the WebSocket or Automerge document.

The synchronization and application paths meet at SwiftData:

```text
Application path
User gesture -> board model -> use case -> repository -> SwiftData -> UI

Synchronization path
WebSocket -> coordinator -> Automerge merge -> projection -> SwiftData -> UI
```

Expose one immutable board-view state from an `@Observable` main-actor model. Combine persisted tasks with synchronization status there. Keep protocol and transport DTOs out of the views.

## Suggested module boundaries

```text
App/
  StillWorxApp
  AppCompositionRoot

Presentation/
  Board/
    BoardView
    BoardModel
    BoardViewState

Domain/
  Models/
  Repositories/
  UseCases/

Data/
  SwiftData/
    TaskRecord
    TaskStore
  Repositories/
    TaskRepositoryImpl

Sync/
  SyncCoordinator
  SyncTransport
  URLSessionSyncTransport
  CrdtStore
  AutomergeCrdtStore
  SyncModels
```

Exact names may differ, but preserve the ownership boundaries.

## SwiftData model

Persist tasks with these fields:

```text
id: String             // UUID string
title: String
column: String         // TODO, DOING, or DONE
updatedAt: Int64       // epoch milliseconds
isDeleted: Bool        // tombstone
syncPending: Bool
```

Only active rows appear on the board. Sort them deterministically by `updatedAt` ascending and then `id` ascending.

Every local mutation must:

1. update SwiftData first in one save operation
2. assign a fresh epoch-millisecond `updatedAt`
3. set `syncPending = true`
4. notify the synchronization coordinator after persistence succeeds

Deletion is a tombstone update, not a physical removal.

`syncPending` means the local persistence mutation has not yet been folded into the locally persisted Automerge document. Clear it only after applying the change to the document and saving the document. It is not a network acknowledgement flag.

## Shared CRDT document

Use this Automerge schema exactly:

```text
ROOT
  tasks: Map<taskId, Map>
    id: String
    title: String
    column: String
    updatedAt: Automerge timestamp/Date
    deleted: Boolean
```

Keep `syncPending` in SwiftData only.

Persist one full Automerge binary document per board in Application Support, under an app-private `crdt` directory with a filename such as:

```text
Application Support/crdt/<boardId>.automerge
```

Write a temporary file and atomically replace the previous document. Create excluded-from-backup or file-protection attributes as appropriate for prototype data. Serialize all document access inside the CRDT actor and preserve the full Automerge history. Never flatten the document to JSON and reconstruct it.

Automerge decides convergence. Do not implement timestamp-based last-write-wins logic. When a field has concurrent values, inspect the Automerge conflicts, log their existence without sensitive content, and project Automerge's deterministic selected value. `updatedAt` is display/order metadata, not conflict authority.

Pay close attention to cross-language scalar types. The timestamp must be written as an Automerge timestamp/Date compatible with the Android client, not as an arbitrary string or floating-point field.

## Synchronization coordinator

Implement a single-consumer coordinator, preferably as an actor with an explicit event queue. Startup, local-change, transport, approval, and shutdown events must be handled serially so the state machine and CRDT document cannot race.

### Startup

1. Load or create the persisted Automerge document.
2. Read SwiftData rows with `syncPending = true` and apply them to the document.
3. Persist the document and clear the processed pending flags.
4. Project the document into SwiftData.
5. Start the WebSocket connection.

### Local change

1. Fetch pending SwiftData rows as immutable snapshots.
2. Apply them to the Automerge document.
3. Persist the document.
4. Clear the processed `syncPending` flags.
5. If connected and synchronization is not awaiting approval, send the current full document.

When reconnect approval is pending, local edits remain fully available. Fold them into the locally persisted CRDT, but do not send them through the normal local-change path until the user chooses `SYNC`.

### Normal remote document

When connected normally and not awaiting approval:

1. Flush pending local rows into Automerge first.
2. Decode and merge the remote document.
3. Persist the merged document.
4. Project it into SwiftData in one save operation.

SwiftData observation then drives an animated UI update.

### Reconnect requiring approval

Track whether the app has connected successfully before and whether it subsequently went offline.

After a connection opens following that offline period:

- set `syncPausedForApproval = true`
- expose that user approval is required
- send the current local document once
- stage incoming document bytes as independent `Data` values in arrival order
- do not merge or project staged documents yet

The initial connection after app startup does not require approval.

When the user selects `SYNC`:

1. Flush pending SwiftData rows into the local document.
2. Merge all staged documents in arrival order.
3. Clear the staging buffer.
4. Persist the merged document.
5. Project the result into SwiftData once.
6. Clear the paused/approval state.
7. Send the current document if the socket is connected.

## WebSocket protocol

Match the Android text protocol byte-for-byte at the envelope level. Send and receive UTF-8 JSON containing a Base64-encoded full Automerge document:

```json
{
  "type": "sync",
  "boardId": "demo-board",
  "document": "<base64 full Automerge document>"
}
```

Ignore messages with another `type` or `boardId`. Log and ignore malformed JSON, invalid Base64, and invalid Automerge bytes without crashing or replacing the valid local document.

This prototype sends full documents, not Automerge incremental sync messages.

## Connection and retry behavior

Expose three connection states:

```text
Disconnected
Connecting
Connected
```

Connect automatically on startup. An automatic connection cycle consists of the initial attempt plus up to three automatic retries, with a fixed two-second delay between failures. After exhaustion, stop automatic attempts and expose that manual reconnection is required.

Selecting `RETRY` performs one manual connection attempt. If it fails, return directly to the manual-retry state. Do not restart the automatic retry loop. Reset counters when the socket opens.

Configure WebSocket keepalive behavior as closely as the platform API permits, targeting the Android client's 20-second ping cadence. If `URLSessionWebSocketTask` requires explicit ping scheduling, own that task inside the transport and cancel it on disconnect.

Treat an open WebSocket as connected. The protocol contains no document acknowledgement.

## Connection UI

Show a compact connection capsule at the top of the board:

- red dot and `Disconnected`
- amber dot and `Connecting...`
- green dot and `Connected`

Animate the three dots in `Connecting...` on an approximately 400 ms cadence. Reserve space for all three dots and make inactive dots faint so the capsule width remains stable. Do not show a progress spinner or a separate connecting banner.

Use compact banners with only enough vertical space for the message and CTA, approximately a 48 pt minimum height. Use semibold message text.

Banner behavior:

- While offline, show a persistent amber banner: `You’re offline. Changes are saved locally.`
- Once automatic retries are exhausted, add a filled, high-contrast, capsule-shaped `RETRY` CTA.
- On an ordinary successful connection, show a green `Successfully connected.` banner temporarily for about three seconds.
- On a successful reconnection requiring approval, show a green banner: `Successfully reconnected. Sync to apply updates from other devices.` with a filled, high-contrast, capsule-shaped `SYNC` CTA.
- Keep the reconnect banner visible until the user approves synchronization.
- Show no banner while connecting.

CTA labels are uppercase and exactly `RETRY` and `SYNC`.

## SwiftUI quality and accessibility

- Use stable task IDs in `ForEach`.
- Animate durable remote insertions, removals, and moves without representing remote in-progress drags.
- Keep connection-dot animation isolated so it does not cause the board to redraw.
- Keep badge and banner dimensions stable through state changes where practical.
- Mark column titles as accessibility headings.
- Give controls clear labels and hints.
- Add custom accessibility actions for moving tasks between columns and positions.
- Meet the 44 pt minimum interactive target.
- Support Dynamic Type without clipping status labels, messages, or CTAs.
- Respect Reduce Motion by simplifying nonessential card and status animations.

## Build-time configuration

Declare these build settings:

```text
SYNC_SERVER_URL
SYNC_BOARD_ID
```

Expose them through Info.plist substitutions:

```text
SyncServerURL = $(SYNC_SERVER_URL)
SyncBoardID = $(SYNC_BOARD_ID)
```

Provide a committed example `.xcconfig` and an ignored local `.xcconfig` for developer-specific values. Resolve configuration in this order:

1. process environment overrides for UI tests and local schemes
2. Info.plist values populated from build settings
3. development defaults

Use defaults equivalent to Android:

```text
SYNC_SERVER_URL=ws://127.0.0.1:8080
SYNC_BOARD_ID=demo-board
```

The loopback default is suitable for a simulator only when the server is reachable from that simulator environment. For a physical iPhone, use the development computer's LAN IPv4 address, for example:

```text
SYNC_SERVER_URL=ws://192.168.1.50:8080
SYNC_BOARD_ID=demo-board
```

The phone and computer must be on the same Wi-Fi network, the server must listen on `0.0.0.0`, and the host firewall must allow inbound TCP traffic on the selected port.

If plain `ws://` requires an App Transport Security exception, scope it to Debug/local development. Do not weaken production transport security globally. Do not commit machine-specific configuration or secrets.

## Required tests

Add focused unit and UI tests for:

### SwiftData

- active queries exclude tombstones
- ordering is deterministic
- local mutations set `syncPending`
- CRDT projection updates, inserts, and tombstones correctly

### Automerge store

- create, save, and reload a document
- retain full history through persistence
- merge divergent offline documents
- inspect concurrent field values while preserving deterministic projection
- safely reject malformed remote bytes

### Coordinator

- startup flushes pending changes before connecting
- local changes update SwiftData before the CRDT
- normal remote changes flush local pending state before merge
- initial connection does not require approval
- reconnect after offline stages incoming documents
- local changes remain usable while approval is pending
- `SYNC` merges staged documents and projects once
- automatic retry exhaustion exposes manual retry
- manual retry failure does not restart automatic retries
- cancellation closes the socket, ping loop, and coordinator tasks

### SwiftUI

- each connection state displays the correct dot and label
- connecting has no banner or spinner
- offline and reconnect banners contain the correct CTA
- the reconnect banner persists until approval
- drag/drop commits a durable move
- accessibility movement actions work
- Dynamic Type does not clip banners or CTAs
- Reduce Motion is respected

## Cross-platform binary compatibility

Use committed binary fixtures to verify both directions:

1. Android creates a document, iOS loads and edits it, and Android reloads the merged binary.
2. iOS creates a document, Android loads and edits it, and iOS reloads the merged binary.

Assert the exact schema types, task IDs, tombstones, Unicode titles, timestamps, divergent offline edits, and at least one concurrent field conflict. This must exercise real Automerge binary encoding; a JSON-only compatibility test is insufficient.

## End-to-end acceptance scenario

1. Connect an Android device and an iPhone to the same board.
2. Create tasks on both and confirm normal synchronization.
3. Take one device offline and edit, move, create, and delete tasks on both devices.
4. Confirm the offline device stays responsive and displays the persistent amber banner.
5. Let automatic retries exhaust and confirm the `RETRY` CTA appears.
6. Restore connectivity and reconnect.
7. Confirm staged remote state does not rearrange the visible board.
8. Confirm the green reconnect banner offers `SYNC`.
9. Make another local edit before approval and verify it remains present.
10. Select `SYNC` and confirm both platforms converge without losing either side's work.
11. Confirm durable remote card changes animate fluidly on the receiving device.

## Documentation deliverables

Keep a short README that documents:

- where `SYNC_SERVER_URL` and `SYNC_BOARD_ID` are declared
- `.xcconfig`, Info.plist, and environment-variable precedence
- simulator and physical-device URL examples
- server binding, firewall, and ATS requirements
- build, test, install, and run steps
- architecture and synchronization diagrams
- reconnect approval behavior
- prototype limitations

Use portable Mermaid syntax in repository documentation. Quote labels containing punctuation and avoid renderer-specific HTML so diagrams render across common GitHub browsers.

## Explicit prototype limitations

Document these limitations:

- full Automerge documents are transmitted rather than incremental sync messages
- there is no authentication, authorization, application-level encryption, or board discovery
- the server is a simple relay/store and the client has no document acknowledgement
- background synchronization is not guaranteed
- retries use a fixed delay without jitter or exponential backoff
- live pointer and in-progress drag state are not synchronized
- tombstones have no garbage-collection policy
- Automerge convergence does not guarantee a preferred business outcome for concurrent edits

## Definition of done

The iOS app is complete when it matches the Android user experience and protocol, remains local-first, persists SwiftData and Automerge state across restarts, interoperates with Android over Wi-Fi, stages remote changes after reconnect until `SYNC`, animates received board changes fluidly, passes the required tests, and clearly documents configuration and architectural tradeoffs.
