@file:OptIn(ExperimentalMaterial3Api::class)

package com.semenenko.pulseplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.semenenko.pulseplayer.R
import com.semenenko.pulseplayer.data.AppSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Запись в стеке навигации. Уникальный id нужен, чтобы состояние экрана (прокрутка и т.д.) не путалось. */
private class NavEntry(val id: Int, val dest: Dest)

@Composable
fun PulseRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf<NavEntry>() }
    val nextId = remember { intArrayOf(0) }
    val push: (Dest) -> Unit = { stack.add(NavEntry(nextId[0]++, it)) }
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    var showNow by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val stopped by vm.stopped.collectAsStateWithLifecycle()
    val picker by vm.playlistPicker.collectAsStateWithLifecycle()

    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    LaunchedEffect(granted) {
        if (granted) {
            vm.refreshLibrary()
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(ctx.getString(it)) } }
    LaunchedEffect(Unit) { vm.errors.collect { snack.showSnackbar(it) } }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    BackHandler(enabled = showNow) { showNow = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                    if (ui.current != null || stopped) MiniPlayer(ui, vm) { showNow = true }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                // Главный экран всегда остаётся в композиции: вкладка и позиция прокрутки сохраняются.
                HomeScreen(vm, granted, { permLauncher.launch(perm) }, stack.isEmpty()) { push(it) }

                // Открытые экраны лежат слоями поверх; нижние не выгружаются,
                // поэтому «Назад» возвращает ровно на прежнюю позицию.
                stack.forEach { entry ->
                    key(entry.id) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            val top = entry === stack.lastOrNull()
                            when (val d = entry.dest) {
                                is Dest.Tracks -> TrackListScreen(d.title, d.tracks, vm, pop, isTop = top)
                                is Dest.Playlist -> PlaylistScreen(d.id, d.name, vm, pop, top)
                                is Dest.Browse -> BrowseScreen(vm, d, pop) { push(it) }
                                Dest.Picked -> {
                                    val pk by vm.picked.collectAsStateWithLifecycle()
                                    TrackListScreen(
                                        stringResource(R.string.selected_files), pk, vm, pop,
                                        isTop = top,
                                        onRemove = { vm.removePicked(it) },
                                        emptyText = stringResource(R.string.files_empty_title),
                                    )
                                }
                                Dest.Equalizer -> EqualizerScreen(pop)
                                Dest.Settings -> SettingsScreen(vm, pop, push)
                                Dest.Help -> HelpScreen(pop)
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showNow,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(vm, onClose = { showNow = false }, onOpenEq = {
                showNow = false
                if (stack.lastOrNull()?.dest != Dest.Equalizer) push(Dest.Equalizer)
            })
        }

        picker?.let { AddToPlaylistDialog(it, vm) { vm.playlistPicker.value = null } }

        val info by vm.trackInfo.collectAsStateWithLifecycle()
        info?.let { TrackInfoDialog(it) { vm.trackInfo.value = null } }

        val ratePrompt by AppSettings.ratePrompt.collectAsStateWithLifecycle()
        if (ratePrompt) {
            RateDialog(
                onLater = { AppSettings.dismissRatePrompt() },
                onSubmit = { stars ->
                    AppSettings.markRated()
                    if (stars >= 4) openRuStore(ctx) else sendFeedbackMail(ctx, stars)
                },
            )
        }
    }
}
