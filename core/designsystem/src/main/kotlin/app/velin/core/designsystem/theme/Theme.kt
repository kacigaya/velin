package app.velin.core.designsystem.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Velin's root theme. Dynamic color is the default on Android 12+; the generated fallback
 * schemes follow the system contrast setting (Android 14+), because dynamic schemes already do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VelinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = remember(context, darkTheme, dynamicColor) {
        colorScheme(context, darkTheme, dynamicColor)
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

private fun colorScheme(context: Context, darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return when (systemContrast(context)) {
        Contrast.Standard -> if (darkTheme) darkScheme else lightScheme
        Contrast.Medium -> if (darkTheme) darkSchemeMediumContrast else lightSchemeMediumContrast
        Contrast.High -> if (darkTheme) darkSchemeHighContrast else lightSchemeHighContrast
    }
}

private enum class Contrast { Standard, Medium, High }

private fun systemContrast(context: Context): Contrast {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return Contrast.Standard
    // Platform levels are 0 (standard), 0.5 (medium) and 1 (high).
    val level = context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f
    return when {
        level >= 0.75f -> Contrast.High
        level >= 0.25f -> Contrast.Medium
        else -> Contrast.Standard
    }
}
