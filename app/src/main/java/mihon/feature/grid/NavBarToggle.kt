package mihon.feature.grid

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import uy.kohesive.injekt.injectLazy

/**
 * Custom: show/hide the main tab bar (navigation rail on tablets, bottom bar on phones)
 * with a ☰ button in the top bars of the main tabs.
 */
object NavBarToggle {
    private val app: Application by injectLazy()
    private val prefs by lazy { app.getSharedPreferences("cover_grid", Context.MODE_PRIVATE) }

    val visible by lazy { MutableStateFlow(prefs.getBoolean("nav_bar_visible", true)) }

    /** Hidden automatically while the NAS tab is open (not saved); ☰ shows it again. */
    val autoHidden = MutableStateFlow(false)

    fun toggle() {
        if (autoHidden.value) {
            autoHidden.value = false
            if (visible.value) return
        }
        val v = !visible.value
        visible.value = v
        prefs.edit { putBoolean("nav_bar_visible", v) }
    }
}

/** Provided by the home screen for tab content: the ☰ action, or null outside the main tabs. */
val LocalNavBarToggle = staticCompositionLocalOf<(() -> Unit)?> { null }
