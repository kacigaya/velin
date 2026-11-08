package app.velin.feature.mail.mailbox

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.feature.mail.R

data class OverflowAction(val label: String, @param:DrawableRes val icon: Int, val onClick: () -> Unit)

/** Secondary app bar actions. Keeps compact app bars at three visible actions or fewer. */
@Composable
fun OverflowMenu(actions: List<OverflowAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(VelinIcons.MoreVert), stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    leadingIcon = { Icon(painterResource(action.icon), contentDescription = null) },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}
