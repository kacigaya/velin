package app.velin.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialsTest {
    @Test
    fun usesFirstTwoWords() = assertEquals("AL", initialsOf("Ada Lovelace"))

    @Test
    fun usesLocalPartOfAddress() = assertEquals("JD", initialsOf("jane.doe@tuta.com"))

    @Test
    fun keepsCharactersOutsideBmp() = assertEquals("😀", initialsOf("😀 "))

    @Test
    fun fallsBackForBlankInput() = assertEquals("?", initialsOf("  "))
}
