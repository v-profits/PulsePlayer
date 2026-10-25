@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.pulseplayer.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pulseplayer.R
import com.example.pulseplayer.data.AppSettings

private fun currentLanguage(): Int {
    val l = AppCompatDelegate.getApplicationLocales()
    if (l.isEmpty()) return 0
    return if (l.get(0)?.language == "ru") 2 else 1
}

private fun applyLanguage(v: Int) {
    AppCompatDelegate.setApplicationLocales(
        when (v) {
            1 -> LocaleListCompat.forLanguageTags("en")
            2 -> LocaleListCompat.forLanguageTags("ru")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
    )
}

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val theme by AppSettings.theme.collectAsStateWithLifecycle()
    val minLen by AppSettings.minLength.collectAsStateWithLifecycle()
    val keepOn by AppSettings.keepScreenOn.collectAsStateWithLifecycle()
    var lang by remember { mutableIntStateOf(currentLanguage()) }
    var dialog by remember { mutableStateOf<String?>(null) }

    val systemLabel = stringResource(R.string.theme_system)
    val themeOptions = listOf(
        systemLabel to 0,
        stringResource(R.string.theme_light) to 1,
        stringResource(R.string.theme_dark) to 2,
    )
    val langOptions = listOf(
        stringResource(R.string.lang_system) to 0,
        stringResource(R.string.lang_en) to 1,
        stringResource(R.string.lang_ru) to 2,
    )
    val minLenOptions = listOf(stringResource(R.string.min_len_off) to 0) +
        listOf(15, 30, 60, 120).map { stringResource(R.string.seconds_fmt, it) to it }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { BackButton(onBack) },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Header(R.string.settings_appearance)
            Row0(Icons.Rounded.Palette, stringResource(R.string.settings_theme),
                themeOptions.first { it.second == theme }.first) { dialog = "theme" }
            Row0(Icons.Rounded.Language, stringResource(R.string.settings_language),
                langOptions.first { it.second == lang }.first) { dialog = "lang" }

            Header(R.string.settings_library)
            Row0(Icons.Rounded.Timer, stringResource(R.string.settings_min_length),
                minLenOptions.firstOrNull { it.second == minLen }?.first ?: minLenOptions.first().first) { dialog = "minlen" }
            Row0(Icons.Rounded.Refresh, stringResource(R.string.rescan), null) { vm.rescan() }

            Header(R.string.settings_playback)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_keep_screen)) },
                supportingContent = { Text(stringResource(R.string.settings_keep_screen_desc)) },
                leadingContent = { Icon(Icons.Rounded.Smartphone, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { Switch(checked = keepOn, onCheckedChange = { AppSettings.setKeepScreenOn(it) }) },
                modifier = Modifier.clickable { AppSettings.setKeepScreenOn(!keepOn) },
            )

            Header(R.string.settings_about)
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.about_version, version) + "\n" + stringResource(R.string.about_desc)) },
                leadingContent = { Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    when (dialog) {
        "theme" -> ChoiceDialog(stringResource(R.string.settings_theme), themeOptions, theme,
            { dialog = null; AppSettings.setTheme(it) }, { dialog = null })
        "lang" -> ChoiceDialog(stringResource(R.string.settings_language), langOptions, lang,
            { dialog = null; lang = it; applyLanguage(it) }, { dialog = null })
        "minlen" -> ChoiceDialog(stringResource(R.string.settings_min_length), minLenOptions, minLen,
            { dialog = null; AppSettings.setMinLength(it) }, { dialog = null })
    }
}

@Composable
private fun Header(res: Int) {
    Text(stringResource(res), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Row0(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String, options: List<Pair<String, T>>, selected: T,
    onPick: (T) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onPick(value) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
