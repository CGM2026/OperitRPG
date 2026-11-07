package com.ai.assistance.operit.rpg.ui.avatar

enum class AvatarKind { CHARACTER, CAMPAIGN }

data class AvatarSpec(val kind: AvatarKind, val glyphId: String, val pairId: String)
