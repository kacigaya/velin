package app.velin.feature.mail.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.feature.mail.R
import app.velin.feature.mail.model.FolderUi
import app.velin.feature.mail.model.LabelUi

/**
 * Bottom sheet to pick a move target. The caller leaves out the folder the mails are in,
 * so every row is a real move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToSheet(
    folders: List<FolderUi>,
    onPick: (FolderUi) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = expandedSheetState()) {
        SheetTitle(stringResource(R.string.picker_move_title))
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(folders, key = { it.id }) { folder ->
                ListItem(
                    onClick = { onPick(folder) },
                    leadingContent = { Icon(painterResource(folderIcon(folder.kind)), contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.padding(start = (16 * folder.depth.coerceAtMost(4)).dp),
                ) { Text(folderName(folder), maxLines = 1) }
            }
        }
    }
}

/** A label and whether all, some or none of the selected mails carry it. */
@Immutable
data class LabelChoice(val label: LabelUi, val state: ToggleableState)

/**
 * Tri-state label editor for one or many mails. A label the selected mails disagree on starts
 * as indeterminate and stays untouched unless the user changes it; [onApply] receives only the
 * labels to add to and remove from every selected mail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelSheet(
    choices: List<LabelChoice>,
    onApply: (added: Set<String>, removed: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val edited = remember { mutableStateMapOf<String, ToggleableState>() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = expandedSheetState()) {
        SheetTitle(stringResource(R.string.picker_labels_title))
        if (choices.isEmpty()) {
            Text(
                stringResource(R.string.picker_labels_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(choices, key = { it.label.id }) { choice ->
                val current = edited[choice.label.id] ?: choice.state
                // Indeterminate is reachable only from the original state, never by tapping.
                val next = if (current == ToggleableState.On) ToggleableState.Off else ToggleableState.On
                ListItem(
                    onClick = { edited[choice.label.id] = next },
                    leadingContent = {
                        Icon(
                            painterResource(VelinIcons.LabelFilled),
                            contentDescription = null,
                            tint = choice.label.colorHex?.let(::parseHexColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    // The checkbox only shows state (and announces it); the whole row toggles.
                    trailingContent = { TriStateCheckbox(state = current, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) { Text(choice.label.name, maxLines = 1) }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = {
                    val (added, removed) = labelChanges(choices, edited)
                    onApply(added, removed)
                },
                enabled = choices.isNotEmpty(),
            ) { Text(stringResource(R.string.action_apply)) }
        }
    }
}

/** Only labels whose state the user changed count; untouched rows send nothing. */
internal fun labelChanges(choices: List<LabelChoice>, edited: Map<String, ToggleableState>): Pair<Set<String>, Set<String>> {
    val added = mutableSetOf<String>()
    val removed = mutableSetOf<String>()
    for (choice in choices) {
        val state = edited[choice.label.id] ?: continue
        if (state == choice.state) continue
        when (state) {
            ToggleableState.On -> added += choice.label.id
            ToggleableState.Off -> removed += choice.label.id
            ToggleableState.Indeterminate -> Unit
        }
    }
    return added to removed
}

/** On when every selected mail has the label, off when none does, indeterminate otherwise. */
internal fun labelChoices(labels: List<LabelUi>, selectedMailLabels: List<Set<String>>): List<LabelChoice> =
    labels.map { label ->
        val count = selectedMailLabels.count { label.id in it }
        val state = when {
            selectedMailLabels.isEmpty() || count == 0 -> ToggleableState.Off
            count == selectedMailLabels.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
        LabelChoice(label, state)
    }

/** Opens fully; a half-open stop adds nothing for short pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun expandedSheetState() = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
)

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}
