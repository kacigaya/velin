package app.velin.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND

/** True when the folder pane stays visible next to the content (840dp and wider). */
@Composable
fun isFolderPanePermanent(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)

/**
 * Places [folderPane] as a modal drawer on compact and medium widths and as a permanent side
 * pane on expanded widths. Mail list and conversation adapt separately via list-detail scenes.
 */
@Composable
fun AdaptiveShell(
    drawerState: DrawerState,
    permanent: Boolean,
    folderPane: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (permanent) {
        Row {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(FOLDER_PANE_WIDTH).fillMaxHeight(),
            ) { folderPane() }
            content()
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Edge swipes stay free for predictive back; the drawer opens from the menu button.
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(drawerState = drawerState, modifier = Modifier.width(FOLDER_PANE_WIDTH)) { folderPane() }
            },
            content = content,
        )
    }
}

private val FOLDER_PANE_WIDTH = 320.dp
