package app.velin.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Initials avatar. The container role comes from a stable hash of the address, so one sender
 * keeps one color across lists and themes. Decorative: the sender name is always shown next to it.
 */
@Composable
fun SenderAvatar(
    name: String,
    address: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val initials = remember(name, address) { initialsOf(name.ifBlank { address }) }
    val (hashedContainer, hashedContent) = avatarColors(address)
    val container = containerColor.takeOrElse { hashedContainer }
    val content = contentColor.takeOrElse { hashedContent }
    Box(
        modifier = modifier
            .size(size)
            .background(container, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = content,
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun avatarColors(address: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (Math.floorMod(address.lowercase().hashCode(), 3)) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
}

internal fun initialsOf(text: String): String {
    val words = text.trim().substringBefore('@').split(' ', '.', '_', '-').filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> firstCodePoint(words[0]).uppercase()
        else -> (firstCodePoint(words[0]) + firstCodePoint(words[1])).uppercase()
    }
}

// Code point, not Char, so names starting with characters outside the BMP stay intact.
private fun firstCodePoint(word: String): String = String(Character.toChars(word.codePointAt(0)))
