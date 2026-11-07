package com.ai.assistance.operit.rpg.ui.theme

import com.ai.assistance.operit.rpg.ui.paper.Paper
import com.ai.assistance.operit.rpg.ui.paper.PaperInk
import com.ai.assistance.operit.rpg.ui.paper.PaperLine
import com.ai.assistance.operit.rpg.ui.paper.PaperMuted
import com.ai.assistance.operit.rpg.ui.paper.paperTone

// Legacy D20 names kept for existing call sites; new UI reads Paper* from rpg.ui.paper.
val D20Paper = Paper
val D20Surface = Paper
val D20Ink = PaperInk
val D20InkMuted = PaperMuted
val D20Divider = PaperLine
val D20Accent = paperTone("sage").color
