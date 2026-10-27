package zenith.app

import android.content.Context
import android.content.Intent

/** A navigation app Zenith runs behind (Waze, Google Maps). */
data class NavApp(val label: String, val packageName: String) {
    fun launch(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        private val KNOWN = listOf(NavApp("Waze", "com.waze"), NavApp("Maps", "com.google.android.apps.maps"))

        /** Installed ones only (declared in the manifest's <queries> so Android lets us see them). */
        fun installed(context: Context): List<NavApp> =
            KNOWN.filter { context.packageManager.getLaunchIntentForPackage(it.packageName) != null }
    }
}
