@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.pulseplayer.ui

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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PulseRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf<Dest>() }
    var showNow by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val ui by vm.ui.collectAsStateWithLifecycle()
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

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = showNow) { showNow = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                    if (ui.current != null) MiniPlayer(ui, vm) { showNow = true }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (val d = stack.lastOrNull()) {
                    null -> HomeScreen(vm, granted, { permLauncher.launch(perm) }) { stack.add(it) }
                    is Dest.Tracks -> TrackListScreen(d.title, d.tracks, vm, { stack.removeAt(stack.lastIndex) })
                    is Dest.Playlist -> PlaylistScreen(d.id, d.name, vm) { stack.removeAt(stack.lastIndex) }
                    Dest.Equalizer -> EqualizerScreen { stack.removeAt(stack.lastIndex) }
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
                if (stack.lastOrNull() != Dest.Equalizer) stack.add(Dest.Equalizer)
            })
        }

        picker?.let { AddToPlaylistDialog(it, vm) { vm.playlistPicker.value = null } }
    }
}
