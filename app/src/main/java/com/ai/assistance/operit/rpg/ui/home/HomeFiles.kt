package com.ai.assistance.operit.rpg.ui.home

import java.io.InputStream
import java.io.OutputStream

/** Blocking campaign archive operations; callers run them on the IO dispatcher. */
interface HomeFiles {
    fun exportBackup(campaignId: String, output: OutputStream)
    fun exportReport(campaignId: String, output: OutputStream)
    fun exportRecap(campaignId: String, output: OutputStream)
    fun importBackup(input: InputStream): String
}
