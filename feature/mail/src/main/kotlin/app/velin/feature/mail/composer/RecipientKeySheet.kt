package app.velin.feature.mail.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.feature.mail.R

/**
 * Manual key check for one recipient, as upstream VerificationByManualInputPage: show the pinned fingerprint,
 * the user compares it with the one in the recipient's own app, then confirms or rejects it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecipientKeySheet(key: RecipientKeyUi, onEvent: (ComposerEvent) -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = { onEvent(ComposerEvent.CloseRecipientKey) }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.key_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(key.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TrustLine(key)
            when {
                key.rejected -> Text(stringResource(R.string.key_sheet_rejected), style = MaterialTheme.typography.bodyMedium)
                key.trust == KeyTrustUi.Changed -> Text(stringResource(R.string.key_sheet_changed), style = MaterialTheme.typography.bodyMedium)
                key.trust == KeyTrustUi.NoKey -> Text(stringResource(R.string.key_sheet_no_key), style = MaterialTheme.typography.bodyMedium)
                key.trust == KeyTrustUi.Unsupported ->
                    Text(stringResource(R.string.key_sheet_unsupported), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Fingerprint(key.fingerprint)
                    Text(stringResource(R.string.key_sheet_compare), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (key.failed) {
                Text(
                    stringResource(R.string.key_sheet_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Actions(key, onEvent)
        }
    }
}

@Composable
private fun TrustLine(key: RecipientKeyUi) {
    val colors = MaterialTheme.colorScheme
    val (icon, text, color) = when {
        key.rejected -> Triple(VelinIcons.Warning, R.string.key_trust_rejected, colors.error)
        key.trust == KeyTrustUi.Changed -> Triple(VelinIcons.Warning, R.string.key_trust_changed, colors.error)
        key.trust == KeyTrustUi.Verified -> Triple(VelinIcons.Key, R.string.key_trust_verified, colors.primary)
        key.trust == KeyTrustUi.FirstUse -> Triple(VelinIcons.Key, R.string.key_trust_first_use, colors.onSurfaceVariant)
        else -> Triple(VelinIcons.Key, R.string.key_trust_none, colors.onSurfaceVariant)
    }
    // A live region announces the new state after a confirm or reset.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Fingerprint(fingerprint: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        if (fingerprint == null) {
            val loading = stringResource(R.string.key_sheet_loading)
            LoadingIndicator(Modifier.padding(16.dp).semantics { contentDescription = loading })
        } else {
            // Upstream FingerprintRow uses groups of four. Four groups per line fit a phone without wrapping.
            val lines = fingerprint.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }
            SelectionContainer {
                Text(
                    lines,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Actions(key: RecipientKeyUi, onEvent: (ComposerEvent) -> Unit) {
    val enabled = !key.busy
    val remove = { onEvent(ComposerEvent.RemoveRecipient(key.field, key.address)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            key.rejected -> Button(onClick = remove, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.key_action_remove))
            }
            key.trust == KeyTrustUi.Changed -> {
                // Upstream RecipientKeyVerificationRecoveryInfoPage recommends rejecting the new key.
                Button(onClick = remove, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.key_action_remove_recommended))
                }
                OutlinedButton(onClick = { onEvent(ComposerEvent.ResetRecipientKey) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.key_action_trust_new))
                }
            }
            key.trust == KeyTrustUi.FirstUse && key.fingerprint != null -> {
                Button(onClick = { onEvent(ComposerEvent.ConfirmRecipientKey) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.key_action_matches))
                }
                OutlinedButton(onClick = { onEvent(ComposerEvent.RejectRecipientKey) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.key_action_no_match))
                }
            }
        }
    }
}
