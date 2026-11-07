package com.ai.assistance.operit.rpg.ui.home

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Default system-file-picker names: `<战役标题>-<yyyyMMdd>.<扩展名>`. */
object ExportNames {
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")

    fun fileName(title: String, date: LocalDate, extension: String): String {
        val safeTitle = ILLEGAL.replace(title, "_").trim()
        return "$safeTitle-${date.format(DATE)}.$extension"
    }
}
