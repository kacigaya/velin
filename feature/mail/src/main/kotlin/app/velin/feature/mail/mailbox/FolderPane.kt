package app.velin.feature.mail.mailbox

import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.component.SenderAvatar
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.feature.mail.R
import app.velin.feature.mail.model.AccountUi
import app.velin.feature.mail.model.FolderKind
import app.velin.feature.mail.model.FolderUi
import app.velin.feature.mail.model.LabelUi
import kotlinx.collections.immutable.ImmutableList

data class FolderPaneState(
    val account: AccountUi?,
    val systemFolders: ImmutableList<FolderUi>,
    val customFolders: ImmutableList<FolderUi>,
    val labels: ImmutableList<LabelUi>,
    /** Folder or label id. */
    val selectedId: String?,
)

/**
 * Folder and label list. Used as modal drawer content on compact and medium widths and as a
 * permanent pane on expanded widths, so it carries no container of its own.
 */
@Composable
fun FolderPane(
    state: FolderPaneState,
    onFolderClick: (FolderUi) -> Unit,
    onLabelClick: (LabelUi) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemPadding = NavigationDrawerItemDefaults.ItemPadding
    LazyColumn(
        modifier = modifier,
        contentPadding = WindowInsets.safeDrawing.asPaddingValues().let {
            PaddingValues(top = it.calculateTopPadding() + 12.dp, bottom = it.calculateBottomPadding() + 12.dp)
        },
    ) {
        state.account?.let { account ->
            item(key = "account") { AccountHeader(account) }
        }
        items(state.systemFolders, key = { it.id }) { folder ->
            FolderItem(folder, selected = folder.id == state.selectedId, onClick = { onFolderClick(folder) }, Modifier.padding(itemPadding))
        }
        if (state.customFolders.isNotEmpty()) {
            item(key = "foldersHeader") { SectionHeader(stringResource(R.string.folders_your_folders)) }
            items(state.customFolders, key = { it.id }) { folder ->
                FolderItem(
                    folder = folder,
                    selected = folder.id == state.selectedId,
                    onClick = { onFolderClick(folder) },
                    // Indent nested folders by one 16dp step per level, capped so names stay readable.
                    modifier = Modifier.padding(itemPadding).padding(start = (16 * folder.depth.coerceAtMost(4)).dp),
                )
            }
        }
        if (state.labels.isNotEmpty()) {
            item(key = "labelsHeader") { SectionHeader(stringResource(R.string.folders_labels)) }
            items(state.labels, key = { "label:" + it.id }) { label ->
                NavigationDrawerItem(
                    label = { Text(label.name, maxLines = 1) },
                    icon = { Icon(painterResource(VelinIcons.Label), contentDescription = null, tint = labelTint(label)) },
                    selected = label.id == state.selectedId,
                    onClick = { onLabelClick(label) },
                    modifier = Modifier.padding(itemPadding),
                )
            }
        }
        item(key = "divider") { HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) }
        item(key = "settings") {
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.settings)) },
                icon = { Icon(painterResource(VelinIcons.Settings), contentDescription = null) },
                selected = false,
                onClick = onSettingsClick,
                modifier = Modifier.padding(itemPadding),
            )
        }
    }
}

@Composable
private fun FolderItem(folder: FolderUi, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NavigationDrawerItem(
        label = { Text(folderName(folder), maxLines = 1) },
        icon = { Icon(painterResource(folderIcon(folder.kind, filled = selected)), contentDescription = null) },
        badge = if (folder.unreadCount > 0) {
            { Text(unreadBadge(folder.unreadCount), style = MaterialTheme.typography.labelLarge) }
        } else {
            null
        },
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun AccountHeader(account: AccountUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SenderAvatar(name = account.displayName, address = account.address)
        Column {
            Text(account.displayName.ifBlank { account.address }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (account.displayName.isNotBlank()) {
                Text(
                    account.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun labelTint(label: LabelUi) =
    label.colorHex?.let(::parseHexColor) ?: MaterialTheme.colorScheme.onSurfaceVariant

internal fun unreadBadge(count: Int): String = if (count > 999) "999+" else count.toString()

@DrawableRes
internal fun folderIcon(kind: FolderKind, filled: Boolean = false): Int = when (kind) {
    FolderKind.Inbox -> if (filled) VelinIcons.InboxFilled else VelinIcons.Inbox
    FolderKind.Drafts -> if (filled) VelinIcons.DraftFilled else VelinIcons.Draft
    FolderKind.Sent -> if (filled) VelinIcons.SendFilled else VelinIcons.Send
    FolderKind.Scheduled -> VelinIcons.ScheduleSend
    FolderKind.Archive -> if (filled) VelinIcons.ArchiveFilled else VelinIcons.Archive
    FolderKind.Spam -> if (filled) VelinIcons.ReportFilled else VelinIcons.Report
    FolderKind.Trash -> if (filled) VelinIcons.DeleteFilled else VelinIcons.Delete
    FolderKind.Custom -> if (filled) VelinIcons.FolderFilled else VelinIcons.Folder
}

/** System folders use localized names; Tuta stores them with fixed server-side names. */
@Composable
internal fun folderName(folder: FolderUi): String = folderName(LocalContext.current.resources, folder)

/** Same as the composable version, for code outside composition such as snackbar handlers. */
fun folderName(resources: Resources, folder: FolderUi): String = when (folder.kind) {
    FolderKind.Inbox -> resources.getString(R.string.folder_inbox)
    FolderKind.Drafts -> resources.getString(R.string.folder_drafts)
    FolderKind.Sent -> resources.getString(R.string.folder_sent)
    FolderKind.Scheduled -> resources.getString(R.string.folder_scheduled)
    FolderKind.Archive -> resources.getString(R.string.folder_archive)
    FolderKind.Spam -> resources.getString(R.string.folder_spam)
    FolderKind.Trash -> resources.getString(R.string.folder_trash)
    FolderKind.Custom -> folder.name
}
