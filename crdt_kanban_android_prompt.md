# CRDT Kanban Prototype --- Android Prompt

Build a small native Android collaborative Kanban application
demonstrating an offline-first architecture with synchronization as a
first-class architectural layer.

This is a research/prototype application, but its architecture should
resemble a realistic mobile application rather than an Automerge demo.

The core architectural idea being explored is:

-   The application always interacts with local data.
-   Room is the application's local source of truth.
-   Network availability never determines whether normal local
    application functionality is available.
-   Synchronization is a separate architectural concern.
-   Automerge/CRDT is an implementation detail of the synchronization
    layer.
-   WebSocket is only the transport mechanism.

Use Clean Architecture with four explicit areas:

``` text
presentation/
domain/
data/
sync/
```

Do not over-engineer beyond those boundaries.

## TECHNOLOGY

Use:

-   Kotlin
-   Jetpack Compose
-   Coroutines
-   Flow / StateFlow
-   Room
-   ViewModel
-   MVVM
-   Automerge Java/Kotlin bindings
-   OkHttp WebSocket

Use simple manual dependency construction unless DI becomes genuinely
useful. Do not introduce Hilt just for the sake of it.

Before using Automerge APIs, inspect the current official automerge-java
API/source:

https://github.com/automerge/automerge-java

## PRODUCT

Build a single collaborative Kanban board.

Exactly three columns:

``` text
TODO
DOING
DONE
```

Task:

``` text
id: String
title: String
column: TODO | DOING | DONE
updatedAt: timestamp
```

Use locally generated UUIDs.

Support only:

1.  Create task
2.  Edit task title
3.  Move task
4.  Delete task

No authentication. No accounts. No comments. No labels. No attachments.
No due dates. No multiple boards unless boardId is needed for
synchronization.

Keep the UI extremely simple.

## ARCHITECTURAL PRINCIPLE

The primary application path must be:

``` text
Compose
   ↓
ViewModel
   ↓
Use cases
   ↓
TaskRepository
   ↓
Room
```

The synchronization path must be separate:

``` text
Room
  ↕
Sync Layer
  ↕
Automerge
  ↕
WebSocket
  ↕
Sync Server
```

Remote data must never be fetched directly by the ViewModel or normal
repository read path.

The UI observes Room-backed Flow data.

If the network disappears, the primary application path remains
unchanged.

## PACKAGE STRUCTURE

Use approximately:

``` text
presentation/
    BoardScreen.kt
    BoardViewModel.kt
    BoardUiState.kt

domain/
    model/
        Task.kt
        TaskColumn.kt

    repository/
        TaskRepository.kt

    usecase/
        ObserveTasks.kt
        CreateTask.kt
        UpdateTask.kt
        MoveTask.kt
        DeleteTask.kt

data/
    local/
        TaskEntity.kt
        TaskDao.kt
        AppDatabase.kt

    repository/
        TaskRepositoryImpl.kt

sync/
    SyncCoordinator.kt
    SyncTransport.kt
    WebSocketSyncTransport.kt
    CrdtStore.kt
    AutomergeCrdtStore.kt
```

Adjust names if a simpler structure becomes obvious during
implementation.

Do not introduce interfaces where there is no architectural boundary.

## ROOM

Room is the application's local source of truth.

TaskEntity should contain at minimum:

``` text
id
title
column
updatedAt
```

Consider what additional metadata is genuinely necessary for
synchronization/deletion.

TaskDao should expose a Flow of tasks.

All user operations must update Room first.

Example:

``` text
User moves task
    ↓
ViewModel
    ↓
MoveTask
    ↓
TaskRepository
    ↓
Room transaction
    ↓
Flow emits
    ↓
Compose updates immediately
```

This must work identically whether online or offline.

The ViewModel must not ask:

``` text
"is the device online?"
```

before performing a locally supported operation.

## DELETIONS

Do not physically delete synchronized records in a way that makes
deletion impossible to propagate.

Use a simple tombstone approach if necessary, for example:

``` text
isDeleted: Boolean
```

Deleted tasks should not appear in normal UI queries but must remain
available to synchronization until the deletion has propagated.

Keep this implementation minimal.

## SYNC LAYER

Synchronization must be visibly separate from the data layer.

SyncCoordinator is responsible for coordinating:

-   local changes that need synchronization
-   CRDT updates
-   incoming remote CRDT state
-   applying merged state back to Room
-   reconnect synchronization

The sync layer may depend on the data layer/local persistence
infrastructure as needed.

The domain and presentation layers must not depend on the sync layer.

## AUTOMERGE

Automerge belongs inside the sync layer.

Do NOT expose Automerge Document objects outside the sync layer.

Represent the synchronized Kanban state in an Automerge document.

Conceptually:

``` text
Board
  tasks
    task-id
      id
      title
      column
      updatedAt
      deleted
```

The exact Automerge representation should follow the current library API
and CRDT semantics.

The Automerge document is NOT the UI source of truth.

Room is.

Automerge exists to:

-   represent synchronization state
-   retain CRDT history/metadata
-   merge independently modified replicas
-   reconcile concurrent changes

## CRDT PERSISTENCE

Persist the Automerge document locally in app-private storage.

This persistence belongs to CrdtStore/AutomergeCrdtStore.

On app launch:

1.  Load Room normally.
2.  Load/create the Automerge document.
3.  Start SyncCoordinator.
4.  Reconcile the local Room state and local CRDT state safely.
5.  Connect to the sync server when possible.

Do not block the application waiting for synchronization.

## LOCAL MUTATIONS

When the user changes something:

1.  Update Room immediately.
2.  UI updates from Room.
3.  Notify/trigger SyncCoordinator.
4.  SyncCoordinator represents the equivalent change in Automerge.
5.  Persist the Automerge document.
6.  If connected, synchronize it.

The user-visible operation must not depend on step 6 succeeding.

Keep Room and CRDT consistency explicit.

For this prototype, it is acceptable for repository mutations to
explicitly notify SyncCoordinator after a successful Room transaction.

Do not build a complex production outbox unless required.

However, document in the README that a durable transactional outbox
would be worth considering in production to prevent Room/CRDT divergence
after process failure.

## REMOTE MUTATIONS

When remote CRDT state arrives:

``` text
WebSocket
    ↓
SyncTransport
    ↓
SyncCoordinator
    ↓
Automerge merge
    ↓
determine merged task state
    ↓
Room transaction
    ↓
Room Flow emits
    ↓
ViewModel
    ↓
Compose
```

The UI must not need special handling for remote updates.

From the UI's perspective, Room simply changed.

This behaviour is important.

## NETWORKING

Use OkHttp WebSocket.

Allow configuration for:

Android emulator:

``` text
ws://10.0.2.2:8080
```

Physical device:

``` text
ws://<development-machine-LAN-IP>:8080
```

Use a deliberately simple synchronization envelope.

Client → server:

``` json
{
  "type": "sync",
  "boardId": "demo-board",
  "document": "<base64 Automerge document>"
}
```

Server → client:

``` json
{
  "type": "sync",
  "boardId": "demo-board",
  "document": "<base64 Automerge document>"
}
```

This is intentionally a prototype transport.

Do not implement a custom CRDT.

Do not implement application-level merge logic that defeats the purpose
of Automerge.

## CONNECTION STATE

The UI may display:

``` text
Connected
Disconnected
Connecting
```

This is informational only.

Do not disable:

``` text
create
edit
move
delete
```

because synchronization is unavailable.

A small banner such as:

``` text
Offline — changes will sync when connection is restored
```

is appropriate.

## SYNC SCENARIO

The following must work.

Start Android Device A and Android Device B.

Both use:

``` text
boardId = demo-board
```

Initial synchronized state:

``` text
TODO
- Write article
- Draw diagram
```

Disconnect both from the sync server.

Device A:

``` text
Move Write article → DOING
Create Research CRDTs
```

Device B:

``` text
Move Draw diagram → DONE
Create Review sources
```

Both devices must immediately show their own changes from Room.

Reconnect Device A.

Its local changes synchronize.

Reconnect Device B.

Its independently created changes synchronize.

Automerge reconciles the replicas.

The server broadcasts merged state.

Eventually both Room databases should represent:

``` text
TODO
- Research CRDTs
- Review sources

DOING
- Write article

DONE
- Draw diagram
```

The UI must arrive at this state only because Room was updated by the
synchronization layer.

## CONCURRENT CHANGE TEST

Also test actual concurrent modification.

Initial state:

``` text
Write article → TODO
```

Disconnect both.

Device A:

``` text
Write article → DOING
```

Device B:

``` text
Write article → DONE
```

Reconnect.

Observe Automerge's actual merge semantics.

Do not add arbitrary application conflict resolution merely to produce a
preferred result.

Document what Automerge does.

If the CRDT can contain concurrent values requiring application-level
interpretation, expose/log that clearly.

This is part of the experiment.

## TESTING

Add focused tests.

### DATA

-   Room insert
-   Room update
-   Room move
-   tombstone deletion
-   Flow excludes deleted tasks

### CRDT

-   create CRDT representation
-   mutate CRDT state
-   save/load
-   merge divergent documents

### SYNC

Create two replicas from common state.

Modify replica A.

Modify replica B.

Merge.

Verify independent non-conflicting changes survive.

### ROOM + SYNC

Test:

``` text
remote Automerge state
    ↓
merge
    ↓
Room updated
```

Verify the Room representation matches the merged CRDT state.

## README

Explain the architecture clearly.

Include:

### APPLICATION PATH

``` text
UI
 ↓
Domain
 ↓
Repository
 ↓
Room
```

### SYNCHRONIZATION PATH

``` text
Room
 ↕
Sync Layer
 ↕
Automerge
 ↕
WebSocket
 ↕
Sync Server
```

Explain why Room remains the source of truth for the mobile application.

Explain why Automerge is isolated in the sync layer.

Explain that WebSocket is transport, Automerge handles CRDT
reconciliation, and Room handles normal application
persistence/querying.

Explain limitations of the prototype.

Specifically mention:

-   full Automerge documents are currently transferred
-   production synchronization should preferably use incremental
    change/sync messages
-   production consistency between Room mutations and sync state may
    require a durable outbox
-   authentication/authorization is omitted
-   background synchronization requires additional production
    consideration
-   conflict-free convergence does not necessarily imply
    business-semantic correctness

Do not turn this into a generic Automerge demo.

The final application should look and behave like a normal offline-first
Android application whose synchronization implementation happens to use
CRDT technology.
