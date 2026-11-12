package app.velin.feature.mail.mailbox

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import app.velin.core.designsystem.theme.VelinTheme
import app.velin.feature.mail.screenshot.SampleMail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MailRowActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun labels(trashDeletes: Boolean): List<String> {
        compose.setContent {
            VelinTheme(darkTheme = false, dynamicColor = false) {
                MailRow(SampleMail.mails.first(), selected = false, selectionMode = false, onClick = {}, onLongClick = {},
                    actions = MailRowActions({}, {}, {}, trashDeletes = trashDeletes))
            }
        }
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
            .single().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
    }

    @Test
    fun trashActionSaysWhenItDeletesPermanently() {
        assertEquals(listOf("Archive", "Delete permanently", "Mark as read"), labels(trashDeletes = true))
    }

    @Test
    fun trashActionMovesToTrashElsewhere() {
        assertEquals(listOf("Archive", "Move to trash", "Mark as read"), labels(trashDeletes = false))
    }
}
