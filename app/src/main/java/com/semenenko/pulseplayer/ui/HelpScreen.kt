@file:OptIn(ExperimentalMaterial3Api::class)

package com.semenenko.pulseplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.semenenko.pulseplayer.R

/** Встроенная инструкция для пользователя (Настройки → Инструкция). */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val sections = listOf(
        R.string.help_1_title to R.string.help_1_body,
        R.string.help_2_title to R.string.help_2_body,
        R.string.help_3_title to R.string.help_3_body,
        R.string.help_4_title to R.string.help_4_body,
        R.string.help_5_title to R.string.help_5_body,
        R.string.help_6_title to R.string.help_6_body,
        R.string.help_7_title to R.string.help_7_body,
        R.string.help_8_title to R.string.help_8_body,
        R.string.help_9_title to R.string.help_9_body,
        R.string.help_10_title to R.string.help_10_body,
    )
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.help_title)) }, navigationIcon = { BackButton(onBack) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            sections.forEach { (title, body) ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
