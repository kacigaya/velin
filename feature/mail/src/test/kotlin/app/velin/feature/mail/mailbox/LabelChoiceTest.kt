package app.velin.feature.mail.mailbox

import androidx.compose.ui.state.ToggleableState
import app.velin.feature.mail.model.LabelUi
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelChoiceTest {
    private val work = LabelUi("l/work", "Work", null)
    private val travel = LabelUi("l/travel", "Travel", null)
    private val home = LabelUi("l/home", "Home", null)

    @Test
    fun statesReflectAllSomeOrNone() {
        val choices = labelChoices(listOf(work, travel, home), listOf(setOf("l/work", "l/travel"), setOf("l/work")))
        assertEquals(listOf(ToggleableState.On, ToggleableState.Indeterminate, ToggleableState.Off), choices.map { it.state })
    }

    @Test
    fun onlyUserChangesAreSent() {
        val choices = labelChoices(listOf(work, travel, home), listOf(setOf("l/work", "l/travel"), setOf("l/work")))
        val edited = mapOf("l/work" to ToggleableState.Off, "l/home" to ToggleableState.On, "l/travel" to ToggleableState.Indeterminate)
        assertEquals(setOf("l/home") to setOf("l/work"), labelChanges(choices, edited))
    }

    @Test
    fun togglingBackToTheOriginalSendsNothing() {
        val choices = labelChoices(listOf(work), listOf(setOf("l/work")))
        assertEquals(emptySet<String>() to emptySet<String>(), labelChanges(choices, mapOf("l/work" to ToggleableState.On)))
    }
}
