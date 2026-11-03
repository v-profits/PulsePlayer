package com.semenenko.pulseplayer.data

/**
 * Форматы, которые плеер заведомо не умеет воспроизводить: встроенный движок (ExoPlayer) не открывает их контейнеры.
 * Форматы, зависящие от декодеров конкретного телефона (ALAC, AC3, DTS, MP2), сюда не входят: о них
 * приложение сообщает уже при попытке воспроизведения.
 */
object AudioFormats {
    private val UNSUPPORTED = setOf(
        "wma", "asf", "ape", "wv", "aif", "aiff", "aifc", "mid", "midi", "kar",
        "dsf", "dff", "mpc", "mp+", "mpp", "tta", "tak", "spx", "caf", "ofr", "shn",
    )

    fun extensionOf(name: String?): String = name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    fun isUnsupportedExt(ext: String): Boolean = ext.lowercase() in UNSUPPORTED
}

fun Track.isUnsupportedFormat(): Boolean = AudioFormats.isUnsupportedExt(extension)
