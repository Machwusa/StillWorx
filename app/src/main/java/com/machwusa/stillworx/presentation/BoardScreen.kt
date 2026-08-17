package com.machwusa.stillworx.presentation

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.machwusa.stillworx.R
import com.machwusa.stillworx.domain.model.ConnectionState
import com.machwusa.stillworx.domain.model.Task
import com.machwusa.stillworx.domain.model.TaskColumn
import kotlinx.coroutines.delay

@Composable
fun BoardRoute(viewModel: BoardViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BoardScreen(
        state = state,
        onCreate = viewModel::beginCreate,
        onSyncRequested = viewModel::syncNow,
        onReconnectRequested = viewModel::retryConnection,
        onEdit = viewModel::beginEdit,
        onMove = viewModel::move,
        onDelete = viewModel::requestDelete,
        onEditorTitleChange = viewModel::updateEditorTitle,
        onSaveEditor = viewModel::saveEditor,
        onDismissEditor = viewModel::dismissEditor,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDelete = viewModel::dismissDelete,
        onMessageShown = viewModel::dismissMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    state: BoardUiState,
    onCreate: () -> Unit,
    onSyncRequested: () -> Unit,
    onReconnectRequested: () -> Unit,
    onEdit: (Task) -> Unit,
    onMove: (Task, TaskColumn) -> Unit,
    onDelete: (Task) -> Unit,
    onEditorTitleChange: (String) -> Unit,
    onSaveEditor: () -> Unit,
    onDismissEditor: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current
    var draggedTaskId by remember { mutableStateOf<String?>(null) }
    var edgeScrollDirection by remember { mutableIntStateOf(0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var previousConnectionState by remember { mutableStateOf(state.connectionState) }
    var showConnectionSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(edgeScrollDirection) {
        while (edgeScrollDirection != 0) {
            horizontalScrollState.scrollBy(with(density) { 12.dp.toPx() } * edgeScrollDirection)
            withFrameNanos { }
        }
    }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            snackbarHostState.showSnackbar(context.getString(R.string.save_failed))
            onMessageShown()
        }
    }
    LaunchedEffect(state.connectionState) {
        val justConnected = state.connectionState == ConnectionState.CONNECTED &&
            previousConnectionState != ConnectionState.CONNECTED
        previousConnectionState = state.connectionState
        if (justConnected) {
            showConnectionSuccess = true
            delay(CONNECTION_SUCCESS_BANNER_DURATION_MILLIS)
            showConnectionSuccess = false
        } else if (state.connectionState != ConnectionState.CONNECTED) {
            showConnectionSuccess = false
        }
    }

    val rootDropTarget = remember {
        object : DragAndDropTarget {
            override fun onMoved(event: DragAndDropEvent) {
                val x = event.toAndroidDragEvent().x
                val edge = with(density) { 64.dp.toPx() }
                edgeScrollDirection = when {
                    x < edge -> -1
                    x > viewportSize.width - edge -> 1
                    else -> 0
                }
            }

            override fun onDrop(event: DragAndDropEvent): Boolean = false

            override fun onEnded(event: DragAndDropEvent) {
                draggedTaskId = null
                edgeScrollDirection = 0
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.board_title)) },
                    actions = { ConnectionBadge(state.connectionState) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                AnimatedVisibility(
                    visible = state.connectionState == ConnectionState.DISCONNECTED ||
                        state.syncApprovalRequired || showConnectionSuccess,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    SyncBanner(
                        state = state.connectionState,
                        approvalRequired = state.syncApprovalRequired,
                        manualReconnectRequired = state.manualReconnectRequired,
                        onSyncRequested = onSyncRequested,
                        onReconnectRequested = onReconnectRequested,
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreate,
                modifier = Modifier.semantics {
                    contentDescription = context.getString(R.string.add_task)
                },
            ) { Text("+") }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.tasks.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.drag_instruction),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { viewportSize = it }
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = {
                            it.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
                        },
                        target = rootDropTarget,
                    )
                    .horizontalScroll(horizontalScrollState)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TaskColumn.entries.forEach { column ->
                    KanbanColumn(
                        column = column,
                        tasks = state.tasks.filter { it.column == column },
                        allTasks = state.tasks,
                        draggedTaskId = draggedTaskId,
                        onDragStarted = { draggedTaskId = it },
                        onEdit = onEdit,
                        onMove = onMove,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        TaskEditorDialog(editor, onEditorTitleChange, onSaveEditor, onDismissEditor)
    }
    state.deleteCandidate?.let { task ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.delete_task)) },
            text = { Text(stringResource(R.string.delete_task_confirmation, task.title)) },
            confirmButton = { Button(onClick = onConfirmDelete) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SyncBanner(
    state: ConnectionState,
    approvalRequired: Boolean,
    manualReconnectRequired: Boolean,
    onSyncRequested: () -> Unit,
    onReconnectRequested: () -> Unit,
) {
    val showSyncAction = state == ConnectionState.CONNECTED && approvalRequired
    val showReconnectAction = state == ConnectionState.DISCONNECTED && manualReconnectRequired
    val isSuccessfulConnection = state == ConnectionState.CONNECTED
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val containerColor = when {
        isSuccessfulConnection && darkTheme -> Color(0xFF155D2D)
        isSuccessfulConnection -> Color(0xFFD7F5DC)
        darkTheme -> Color(0xFF5D4200)
        else -> Color(0xFFFFE082)
    }
    val contentColor = when {
        isSuccessfulConnection && darkTheme -> Color(0xFFD7F5DC)
        isSuccessfulConnection -> Color(0xFF0D5F22)
        darkTheme -> Color(0xFFFFE082)
        else -> Color(0xFF5D4200)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                when {
                    showSyncAction -> R.string.reconnect_sync_message
                    isSuccessfulConnection -> R.string.connection_success_message
                    showReconnectAction -> R.string.manual_reconnect_message
                    else -> R.string.offline_sync_message
                },
            ),
            modifier = Modifier.weight(1f),
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
        if (showSyncAction) {
            BannerActionButton(
                label = stringResource(R.string.sync_now),
                containerColor = contentColor,
                contentColor = containerColor,
                onClick = onSyncRequested,
            )
        } else if (showReconnectAction) {
            BannerActionButton(
                label = stringResource(R.string.try_again),
                containerColor = contentColor,
                contentColor = containerColor,
                onClick = onReconnectRequested,
            )
        }
    }
}

@Composable
private fun BannerActionButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ConnectionBadge(state: ConnectionState) {
    val label = when (state) {
        ConnectionState.CONNECTED -> stringResource(R.string.connected)
        ConnectionState.CONNECTING -> stringResource(R.string.connecting)
        ConnectionState.DISCONNECTED -> stringResource(R.string.disconnected)
    }
    val indicatorColor by animateColorAsState(targetValue = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF2E7D32)
        ConnectionState.CONNECTING -> Color(0xFFF9A825)
        ConnectionState.DISCONNECTED -> Color(0xFFC62828)
    }, label = "connection indicator")
    var connectingDotCount by remember(state) { mutableIntStateOf(1) }
    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTING) {
            while (true) {
                delay(CONNECTING_DOT_INTERVAL_MILLIS)
                connectingDotCount = connectingDotCount % 3 + 1
            }
        }
    }
    Row(
        modifier = Modifier
            .padding(end = 12.dp)
            .animateContentSize(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                alignment = Alignment.CenterEnd,
            )
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(indicatorColor),
        )
        if (state == ConnectionState.CONNECTING) {
            val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = buildAnnotatedString {
                    append(label)
                    repeat(3) { dotIndex ->
                        withStyle(
                            SpanStyle(
                                color = labelColor.copy(
                                    alpha = if (dotIndex < connectingDotCount) 1f else 0.25f,
                                ),
                            ),
                        ) { append('.') }
                    }
                },
                color = labelColor,
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun KanbanColumn(
    column: TaskColumn,
    tasks: List<Task>,
    allTasks: List<Task>,
    draggedTaskId: String?,
    onDragStarted: (String) -> Unit,
    onEdit: (Task) -> Unit,
    onMove: (Task, TaskColumn) -> Unit,
    onDelete: (Task) -> Unit,
) {
    var isDragActive by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val currentTasks by rememberUpdatedState(allTasks.associateBy { it.id })
    val currentOnMove by rememberUpdatedState(onMove)
    val dropTarget = remember(column) {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                isDragActive = true
            }

            override fun onEntered(event: DragAndDropEvent) {
                isHovered = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isHovered = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val taskId = event.toAndroidDragEvent().clipData
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
                    ?: return false
                val task = currentTasks[taskId] ?: return false
                if (task.column != column) currentOnMove(task, column)
                isHovered = false
                isDragActive = false
                return true
            }

            override fun onEnded(event: DragAndDropEvent) {
                isHovered = false
                isDragActive = false
            }
        }
    }
    val backgroundColor by animateColorAsState(
        targetValue = if (isHovered) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        label = "column background",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isHovered -> MaterialTheme.colorScheme.primary
            isDragActive -> MaterialTheme.colorScheme.outlineVariant
            else -> Color.Transparent
        },
        label = "column border",
    )

    LazyColumn(
        modifier = Modifier
            .width(280.dp)
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(
                width = if (isHovered) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp),
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = {
                    it.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
                },
                target = dropTarget,
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        item(key = "column-header") {
            Text(
                text = column.label(),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (isHovered) stringResource(R.string.drop_here)
                else stringResource(R.string.task_count, tasks.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        items(items = tasks, key = Task::id) { task ->
            TaskCard(
                task = task,
                isBeingDragged = task.id == draggedTaskId,
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(180),
                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    fadeOutSpec = tween(140),
                ),
                onDragStarted = onDragStarted,
                onEdit = onEdit,
                onMove = onMove,
                onDelete = onDelete,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (tasks.isEmpty()) {
            item(key = "empty-column") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                        .animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(140)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.no_tasks),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    isBeingDragged: Boolean,
    modifier: Modifier = Modifier,
    onDragStarted: (String) -> Unit,
    onEdit: (Task) -> Unit,
    onMove: (Task, TaskColumn) -> Unit,
    onDelete: (Task) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val columnLabels = TaskColumn.entries.associateWith { it.label() }
    val moveActions = TaskColumn.entries
        .filterNot { it == task.column }
        .map { target ->
            CustomAccessibilityAction(
                label = context.getString(R.string.move_task_to, columnLabels.getValue(target)),
                action = {
                    onMove(task, target)
                    true
                },
            )
        }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            )
            .semantics {
                stateDescription = context.getString(
                    R.string.task_in_column,
                    columnLabels.getValue(task.column),
                )
                customActions = moveActions
            }
            .dragAndDropSource { _ ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onDragStarted(task.id)
                DragAndDropTransferData(ClipData.newPlainText(DRAG_LABEL, task.id))
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isBeingDragged) 2.dp else 1.dp,
            color = if (isBeingDragged) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onEdit(task) }) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = { onDelete(task) }) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
private fun TaskEditorDialog(
    editor: TaskEditor,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (editor.isEditing) R.string.edit_task else R.string.add_task))
        },
        text = {
            OutlinedTextField(
                value = editor.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.task_title)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = onSave, enabled = editor.canSave) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TaskColumn.label(): String = when (this) {
    TaskColumn.TODO -> stringResource(R.string.column_todo)
    TaskColumn.DOING -> stringResource(R.string.column_doing)
    TaskColumn.DONE -> stringResource(R.string.column_done)
}

private const val DRAG_LABEL = "stillworx-task"
private const val CONNECTING_DOT_INTERVAL_MILLIS = 400L
private const val CONNECTION_SUCCESS_BANNER_DURATION_MILLIS = 3_000L
