package com.blenouvel.accordeur

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blenouvel.accordeur.audio.AudioEngine
import com.blenouvel.accordeur.audio.ReferenceTone
import com.blenouvel.accordeur.data.SettingsStore
import com.blenouvel.accordeur.data.ThemeMode
import com.blenouvel.accordeur.ui.AppIcons
import com.blenouvel.accordeur.ui.ScalesActions
import com.blenouvel.accordeur.ui.ScalesScreen
import com.blenouvel.accordeur.ui.SettingsActions
import com.blenouvel.accordeur.ui.SettingsScreen
import com.blenouvel.accordeur.ui.TunerScreen
import com.blenouvel.accordeur.ui.TuningSheet
import com.blenouvel.accordeur.ui.theme.AccordeurTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: TunerViewModel = viewModel {
                TunerViewModel(
                    settingsStore = SettingsStore(applicationContext),
                    engine = AudioEngine(applicationContext),
                    referenceTone = ReferenceTone(),
                )
            }
            val scalesViewModel: ScalesViewModel = viewModel {
                ScalesViewModel(settingsStore = SettingsStore(applicationContext), tone = ReferenceTone())
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val darkTheme = when (state.settings.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Icônes de la barre d'état lisibles quel que soit le thème choisi dans l'app.
            LaunchedEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AccordeurTheme(darkTheme = darkTheme, dynamicColor = state.settings.dynamicColor) {
                AccordeurApp(viewModel, scalesViewModel, state)
            }
        }
    }
}

private enum class Screen { TUNER, SCALES, SETTINGS }

@Composable
private fun AccordeurApp(viewModel: TunerViewModel, scalesViewModel: ScalesViewModel, state: TunerUiState) {
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(hasMicPermission(context)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !askedOnce) launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Revérifie au retour (l'utilisateur a pu autoriser le micro dans les réglages du système).
    LifecycleResumeEffect(Unit) {
        granted = hasMicPermission(context)
        onPauseOrDispose { }
    }

    var screen by rememberSaveable { mutableStateOf(Screen.TUNER) }
    var returnTo by rememberSaveable { mutableStateOf(Screen.TUNER) }
    var showTunings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = screen != Screen.TUNER) {
        screen = if (screen == Screen.SETTINGS) returnTo else Screen.TUNER
    }
    val openSettings = {
        returnTo = screen
        screen = Screen.SETTINGS
    }

    // Micro actif seulement pour l'accordeur (et ses réglages), au premier plan : start en
    // onResume, stop en onPause ou en passant sur la vue Gammes.
    if (granted && screen != Screen.SCALES) {
        LifecycleResumeEffect(viewModel) {
            viewModel.start()
            onPauseOrDispose { viewModel.stop() }
        }
    }
    KeepScreenOn(state.settings.keepScreenOn)

    if (screen == Screen.SETTINGS) {
        SettingsScreen(
            state = state,
            actions = SettingsActions(
                setA4 = viewModel::setA4,
                setTolerance = viewModel::setTolerance,
                setNotation = viewModel::setNotation,
                setDetectionMode = viewModel::setDetectionMode,
                setTunerMode = viewModel::setTunerMode,
                setTheme = viewModel::setTheme,
                setDynamicColor = viewModel::setDynamicColor,
                setHaptics = viewModel::setHaptics,
                setKeepScreenOn = viewModel::setKeepScreenOn,
            ),
            onBack = { screen = returnTo },
        )
    } else {
        Scaffold(
            bottomBar = { AppNavigationBar(current = screen, onSelect = { screen = it }) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { inner ->
            Box(
                Modifier
                    .padding(inner)
                    .consumeWindowInsets(inner),
            ) {
                if (screen == Screen.SCALES) {
                    val scalesState by scalesViewModel.uiState.collectAsStateWithLifecycle()
                    ScalesScreen(
                        state = scalesState,
                        actions = ScalesActions(
                            onRootChange = scalesViewModel::setRoot,
                            onScaleChange = scalesViewModel::setScale,
                            onFretsChange = scalesViewModel::setFrets,
                            onLabelsChange = scalesViewModel::setLabels,
                            onLeftHandedChange = scalesViewModel::setLeftHanded,
                            onToggleDegree = scalesViewModel::toggleDegree,
                            onPlay = scalesViewModel::play,
                            onOpenTunings = { showTunings = true },
                            onOpenSettings = openSettings,
                        ),
                    )
                } else if (granted) {
                    TunerScreen(
                        state = state,
                        onOpenSettings = openSettings,
                        onOpenTunings = { showTunings = true },
                        onModeChange = viewModel::setTunerMode,
                        onDetectionModeChange = viewModel::setDetectionMode,
                        onStringTap = viewModel::onStringTapped,
                        onStringLongPress = viewModel::playReferenceTone,
                        onRetry = {
                            viewModel.stop()
                            viewModel.start()
                        },
                    )
                } else {
                    PermissionScreen(
                        permanentlyDenied = askedOnce && !shouldShowRationale(context),
                        onRequest = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                        onOpenSettings = { openAppSettings(context) },
                    )
                }
            }
        }
    }

    if (showTunings) {
        TuningSheet(
            current = state.tuning,
            customTunings = state.settings.customTunings,
            notation = state.settings.notation,
            onSelect = viewModel::selectTuning,
            onSave = viewModel::saveCustomTuning,
            onDelete = viewModel::deleteCustomTuning,
            onDismiss = { showTunings = false },
        )
    }
}

/** Barre de navigation : accordeur / gammes. */
@Composable
private fun AppNavigationBar(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = current == Screen.TUNER,
            onClick = { onSelect(Screen.TUNER) },
            icon = { Icon(AppIcons.Tuner, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_tuner)) },
        )
        NavigationBarItem(
            selected = current == Screen.SCALES,
            onClick = { onSelect(Screen.SCALES) },
            icon = { Icon(AppIcons.Fretboard, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_scales)) },
        )
    }
}

@Composable
private fun PermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(if (permanentlyDenied) R.string.permission_denied else R.string.permission_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.permission_open_settings)) }
        } else {
            Button(onClick = onRequest) { Text(stringResource(R.string.permission_grant)) }
        }
    }
}

/** Écran maintenu allumé pendant l'accordage (réglable). */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun shouldShowRationale(context: Context): Boolean =
    context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: false

private fun openAppSettings(context: Context) {
    val intent = Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
