package com.semenenko.pulseplayer.ui

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.semenenko.pulseplayer.R
import com.semenenko.pulseplayer.data.TrackInfo
import java.util.Date

/** Окно «О треке»: имя файла, формат, расположение в иерархии папок, размер, битрейт, теги. Текст можно выделять и копировать. */
@Composable
fun TrackInfoDialog(info: TrackInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val format = listOf(info.extension.uppercase(), info.mime).filter { it.isNotBlank() }.joinToString(" · ")
    val size = if (info.sizeBytes > 0) Formatter.formatFileSize(ctx, info.sizeBytes) else ""
    val duration = if (info.durationMs > 0) formatTime(info.durationMs) else ""
    val bitrate = if (info.bitrateKbps > 0) "${info.bitrateKbps} kbps" else ""
    val sampleRate = if (info.sampleRateHz > 0) "%.1f kHz".format(info.sampleRateHz / 1000f) else ""
    val modified = if (info.modifiedMs > 0) DateFormat.getMediumDateFormat(ctx).format(Date(info.modifiedMs)) else ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_info)) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoRow(R.string.info_file_name, info.fileName)
                    InfoRow(R.string.info_format, format)
                    InfoRow(R.string.info_location, info.location)
                    InfoRow(R.string.info_size, size)
                    InfoRow(R.string.info_duration, duration)
                    InfoRow(R.string.info_bitrate, bitrate)
                    InfoRow(R.string.info_sample_rate, sampleRate)
                    InfoRow(R.string.info_title, info.title)
                    InfoRow(R.string.info_artist, info.artist)
                    InfoRow(R.string.info_album, info.album)
                    InfoRow(R.string.info_year, info.year)
                    InfoRow(R.string.info_track_no, info.trackNo)
                    InfoRow(R.string.info_modified, modified)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun InfoRow(label: Int, value: String) {
    if (value.isBlank()) return
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
