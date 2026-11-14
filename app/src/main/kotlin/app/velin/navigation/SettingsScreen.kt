package app.velin.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import app.velin.R
import app.velin.core.designsystem.component.SenderAvatar
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.core.domain.Account

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    account: Account,
    signingOut: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onDisplaySettings: () -> Unit,
    onAccessibilitySettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(VelinIcons.ArrowBack), stringResource(R.string.settings_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SenderAvatar(
                            name = account.address.name,
                            address = account.address.address,
                            size = 56.dp,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(account.address.name.ifBlank { account.address.address }, style = MaterialTheme.typography.titleLarge)
                            if (account.address.name.isNotBlank()) Text(account.address.address, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_interface)) {
                    SettingsRow(VelinIcons.Visibility, stringResource(R.string.settings_appearance), stringResource(R.string.settings_display_summary), onDisplaySettings)
                    SettingsRow(VelinIcons.Contrast, stringResource(R.string.settings_contrast), stringResource(R.string.settings_contrast_summary), onAccessibilitySettings)
                    SettingsRow(VelinIcons.Accessibility, stringResource(R.string.settings_accessibility), stringResource(R.string.settings_accessibility_summary), onAccessibilitySettings)
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_privacy)) {
                    SettingsRow(VelinIcons.Image, stringResource(R.string.settings_remote_images), stringResource(R.string.settings_remote_images_summary))
                }
            }
            item {
                TextButton(onClick = { confirmSignOut = true }, enabled = !signingOut, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (signingOut) R.string.settings_signing_out else R.string.sign_out))
                }
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(stringResource(R.string.sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 8.dp).semantics { heading() }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
    }
}

@Composable
private fun SettingsRow(@DrawableRes icon: Int, title: String, summary: String, onClick: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) Icon(
                painterResource(VelinIcons.ExpandMore),
                contentDescription = null,
                modifier = Modifier.rotate(if (LocalLayoutDirection.current == LayoutDirection.Rtl) 90f else -90f),
            )
        }
    }
}
