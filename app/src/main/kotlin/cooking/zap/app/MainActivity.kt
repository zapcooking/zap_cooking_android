package cooking.zap.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cooking.zap.app.repo.InterfacePreferences
import cooking.zap.app.ui.component.NsecPasteGuard
import cooking.zap.app.repo.LocaleRepository
import cooking.zap.app.ui.component.LocalMediaSettings
import cooking.zap.app.ui.component.MediaSettings
import cooking.zap.app.ui.component.PipController
import cooking.zap.app.ui.theme.Themes
import cooking.zap.app.ui.theme.WispTheme

class MainActivity : FragmentActivity() {
    var deepLinkUri = mutableStateOf<String?>(null)
        private set

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)
        val language = prefs.getString("language", "system") ?: "system"
        super.attachBaseContext(LocaleRepository.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        NsecPasteGuard.setActivity(this)
        enableEdgeToEdge()
        deepLinkUri.value = intent?.data?.toString()
        setContent {
            val prefs = remember { getSharedPreferences("wisp_settings", Context.MODE_PRIVATE) }
            val interfacePrefs = remember { InterfacePreferences(this@MainActivity) }
            var appearance by remember {
                mutableStateOf(interfacePrefs.getAppearanceMode())
            }
            // `isSystemInDarkTheme()` recomposes when the OS flips, so SYSTEM
            // tracks it live rather than only at launch.
            val systemDark = isSystemInDarkTheme()
            val isDarkTheme = when (appearance) {
                InterfacePreferences.AppearanceMode.SYSTEM -> systemDark
                InterfacePreferences.AppearanceMode.LIGHT -> false
                InterfacePreferences.AppearanceMode.DARK -> true
            }
            var isLargeText by remember { mutableStateOf(interfacePrefs.isLargeText()) }
            var mediaSettings by remember {
                mutableStateOf(MediaSettings(
                    autoLoadMedia = interfacePrefs.isAutoLoadMedia(),
                    videoAutoPlay = interfacePrefs.isVideoAutoPlay(),
                    mediaLayoutStyle = interfacePrefs.getMediaLayoutStyle()
                ))
            }

            val lightNavScrim = Themes.brand.light.surface.toArgb()
            LaunchedEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(0xFF1F2937.toInt())
                    } else {
                        SystemBarStyle.light(lightNavScrim, 0xFF1F2937.toInt())
                    }
                )
            }

            val pipActive by PipController.pipState.collectAsState()
            val effectiveMediaSettings = if (pipActive != null) {
                mediaSettings.copy(videoAutoPlay = false)
            } else {
                mediaSettings
            }

            WispTheme(isDarkTheme = isDarkTheme, isLargeText = isLargeText) {
                CompositionLocalProvider(LocalMediaSettings provides effectiveMediaSettings) {
                    WispNavHost(
                        deepLinkUri = deepLinkUri.value,
                        onDeepLinkConsumed = { deepLinkUri.value = null },
                        isDarkTheme = isDarkTheme,
                        // The drawer shortcut is a two-state flip, so it lands
                        // on an explicit LIGHT or DARK — leaving SYSTEM behind
                        // is the point of tapping it. SYSTEM is reachable again
                        // from Interface settings.
                        onToggleTheme = {
                            val next = if (isDarkTheme) {
                                InterfacePreferences.AppearanceMode.LIGHT
                            } else {
                                InterfacePreferences.AppearanceMode.DARK
                            }
                            appearance = next
                            interfacePrefs.setAppearanceMode(next)
                        },
                        isLargeText = isLargeText,
                        onInterfaceChanged = {
                            isLargeText = interfacePrefs.isLargeText()
                            appearance = interfacePrefs.getAppearanceMode()
                            mediaSettings = MediaSettings(
                                autoLoadMedia = interfacePrefs.isAutoLoadMedia(),
                                videoAutoPlay = interfacePrefs.isVideoAutoPlay(),
                                mediaLayoutStyle = interfacePrefs.getMediaLayoutStyle()
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkUri.value = intent.data?.toString()
    }
}
