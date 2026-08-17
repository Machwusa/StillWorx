package com.machwusa.stillworx.presentation

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import com.machwusa.stillworx.domain.model.ConnectionState
import com.machwusa.stillworx.domain.model.Task
import com.machwusa.stillworx.domain.model.TaskColumn
import com.machwusa.stillworx.ui.theme.StillWorxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BoardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cardsUseDragInstructionAndAccessibleMoveActionsWithoutArrowButtons() {
        val moves = mutableListOf<TaskColumn>()
        composeRule.setContent {
            StillWorxTheme {
                BoardScreen(
                    state = BoardUiState(
                        tasks = listOf(Task("one", "Write article", TaskColumn.TODO, 1)),
                        connectionState = ConnectionState.DISCONNECTED,
                    ),
                    onCreate = {},
                    onSyncRequested = {},
                    onReconnectRequested = {},
                    onEdit = {},
                    onMove = { _, column -> moves += column },
                    onDelete = {},
                    onEditorTitleChange = {},
                    onSaveEditor = {},
                    onDismissEditor = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithText("Long-press a card, then drag it to another column.").assertExists()
        composeRule.onNodeWithText("DOING ->").assertDoesNotExist()
        val moveNodes = composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        )
        moveNodes.assertCountEquals(1)
        val moveToDoing = moveNodes[0]
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == "Move task to DOING" }
        composeRule.runOnIdle {
            moveToDoing.action()
        }

        composeRule.runOnIdle {
            assertEquals(listOf(TaskColumn.DOING), moves)
        }
    }

    @Test
    fun disconnectedBannerExplainsThatChangesRemainLocal() {
        composeRule.setContent {
            StillWorxTheme {
                BoardScreen(
                    state = BoardUiState(connectionState = ConnectionState.DISCONNECTED),
                    onCreate = {},
                    onSyncRequested = {},
                    onReconnectRequested = {},
                    onEdit = {},
                    onMove = { _, _ -> },
                    onDelete = {},
                    onEditorTitleChange = {},
                    onSaveEditor = {},
                    onDismissEditor = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "You’re offline. Changes are saved locally.",
        ).assertExists()
        composeRule.onNodeWithText("Disconnected").assertExists()
        composeRule.onNodeWithText("SYNC").assertDoesNotExist()
    }

    @Test
    fun exhaustedAutomaticReconnectOffersManualRetry() {
        var reconnectRequests = 0
        composeRule.setContent {
            StillWorxTheme {
                BoardScreen(
                    state = BoardUiState(
                        connectionState = ConnectionState.DISCONNECTED,
                        manualReconnectRequired = true,
                    ),
                    onCreate = {},
                    onSyncRequested = {},
                    onReconnectRequested = { reconnectRequests += 1 },
                    onEdit = {},
                    onMove = { _, _ -> },
                    onDelete = {},
                    onEditorTitleChange = {},
                    onSaveEditor = {},
                    onDismissEditor = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "You’re offline. Changes are saved locally.",
        ).assertExists()
        composeRule.onNodeWithText("RETRY").performClick()
        composeRule.runOnIdle { assertEquals(1, reconnectRequests) }
    }

    @Test
    fun reconnectBannerLetsUserApproveBoardUpdates() {
        var syncRequests = 0
        composeRule.setContent {
            StillWorxTheme {
                BoardScreen(
                    state = BoardUiState(
                        connectionState = ConnectionState.CONNECTED,
                        syncApprovalRequired = true,
                    ),
                    onCreate = {},
                    onSyncRequested = { syncRequests += 1 },
                    onReconnectRequested = {},
                    onEdit = {},
                    onMove = { _, _ -> },
                    onDelete = {},
                    onEditorTitleChange = {},
                    onSaveEditor = {},
                    onDismissEditor = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "Successfully reconnected. Sync to apply updates from other devices.",
        ).assertExists()
        composeRule.onNodeWithText("SYNC").performClick()
        composeRule.runOnIdle { assertEquals(1, syncRequests) }
    }

    @Test
    fun connectingUsesOnlyTheAnimatedStatusBadgeAndSuccessBannerIsTemporary() {
        val connectionState = mutableStateOf(ConnectionState.DISCONNECTED)
        composeRule.setContent {
            StillWorxTheme {
                BoardScreen(
                    state = BoardUiState(connectionState = connectionState.value),
                    onCreate = {},
                    onSyncRequested = {},
                    onReconnectRequested = {},
                    onEdit = {},
                    onMove = { _, _ -> },
                    onDelete = {},
                    onEditorTitleChange = {},
                    onSaveEditor = {},
                    onDismissEditor = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.runOnIdle { connectionState.value = ConnectionState.CONNECTING }

        composeRule.onNodeWithText("You’re offline. Changes are saved locally.").assertDoesNotExist()
        composeRule.onNodeWithText("SYNC").assertDoesNotExist()
        composeRule.onNodeWithText("Connecting", substring = true).assertExists()

        composeRule.runOnIdle { connectionState.value = ConnectionState.CONNECTED }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Successfully connected.").assertExists()
        composeRule.onNodeWithText("Connected").assertExists()

        composeRule.mainClock.advanceTimeBy(3_100)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Successfully connected.").assertDoesNotExist()
    }

    @Test
    fun remotelyMovedCardSettlesInItsUpdatedColumn() {
        val task = Task("one", "Write article", TaskColumn.TODO, 1)
        val boardState = mutableStateOf(
            BoardUiState(tasks = listOf(task), connectionState = ConnectionState.CONNECTED),
        )
        composeRule.setContent {
            StillWorxTheme {
                BoardScreen(
                    state = boardState.value,
                    onCreate = {},
                    onSyncRequested = {},
                    onReconnectRequested = {},
                    onEdit = {},
                    onMove = { _, _ -> },
                    onDelete = {},
                    onEditorTitleChange = {},
                    onSaveEditor = {},
                    onDismissEditor = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.runOnIdle {
            boardState.value = boardState.value.copy(
                tasks = listOf(task.copy(column = TaskColumn.DOING, updatedAt = 2)),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("DOING").performScrollTo()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Write article").assertExists()
        composeRule.onNode(hasStateDescription("In DOING")).assertExists()
    }
}
