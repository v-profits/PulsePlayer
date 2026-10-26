package com.semenenko.pulseplayer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.semenenko.pulseplayer.R

const val FEEDBACK_EMAIL = "vl.profits@gmail.com"

/** Оценка 4–5 — страница приложения в RuStore; 1–3 — письмо разработчику. */
@Composable
fun RateDialog(onLater: () -> Unit, onSubmit: (Int) -> Unit) {
    var stars by remember { mutableIntStateOf(0) }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.rate_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.rate_desc), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    for (i in 1..5) {
                        IconButton(onClick = { stars = i }) {
                            Icon(
                                if (i <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                "$i",
                                Modifier.size(36.dp),
                                tint = if (i <= stars) Color(0xFFFFB300) else cs.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (stars > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(if (stars >= 4) R.string.rate_hint_good else R.string.rate_hint_bad),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = stars > 0, onClick = { onSubmit(stars) }) { Text(stringResource(R.string.rate_send)) }
        },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.rate_later)) } },
    )
}

fun openRuStore(ctx: Context) {
    val pkg = ctx.packageName
    val deep = Intent(Intent.ACTION_VIEW, Uri.parse("rustore://apps.rustore.ru/app/$pkg"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://rustore.ru/catalog/app/$pkg"))
    try {
        ctx.startActivity(deep)
    } catch (e: Exception) {
        try { ctx.startActivity(web) } catch (e2: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.no_store_app), Toast.LENGTH_LONG).show()
        }
    }
}

/** Письмо разработчику; [stars] — оценка (если вызвано из диалога оценки). */
fun sendFeedbackMail(ctx: Context, stars: Int?) {
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    val subject = if (stars != null) ctx.getString(R.string.rate_mail_subject, stars)
    else ctx.getString(R.string.contact_mail_subject)
    val body = "\n\n—\nPulse Player $version\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n${Build.MANUFACTURER} ${Build.MODEL}"
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    try {
        ctx.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(ctx, ctx.getString(R.string.no_mail_app), Toast.LENGTH_LONG).show()
    }
}
