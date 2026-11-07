package com.ai.assistance.operit.rpg.ui.paper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy

/** Centered paper dialog: fixed header and actions, bounded scrolling body, resized above the IME. */
@Composable
fun PaperSheet(
    title: String,
    onDismiss: () -> Unit,
    back: Boolean = false,
    secure: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
    menu: Boolean = false,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val bounds = remember { androidx.compose.runtime.mutableStateOf(Rect.Zero) }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(PaperScrim.copy(alpha = 0.32f))
                .pointerInput(onDismiss) {
                    detectTapGestures { point -> if (!bounds.value.contains(point)) onDismiss() }
                }
                .systemBarsPadding()
                .imePadding()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            val geometry = if (menu) {
                Modifier.width(minOf(300.dp, maxWidth))
            } else {
                Modifier.widthIn(min = 280.dp, max = 560.dp).width(IntrinsicSize.Max)
            }
            Surface(
                geometry
                    .heightIn(max = maxHeight * 0.92f)
                    .onGloballyPositioned { bounds.value = it.boundsInRoot() },
                shape = RoundedCornerShape(14.dp),
                color = Paper,
                contentColor = PaperInk,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp, bottom = 16.dp)) {
                    if (title.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (back) {
                                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Outlined.ArrowBack, "返回", Modifier.size(22.dp))
                                }
                            }
                            Text(
                                title,
                                Modifier.weight(1f),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            headerAction?.invoke()
                            if (!back) {
                                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Outlined.Close, "关闭$title", Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f, false)
                            .verticalScroll(rememberScrollState())
                            .padding(top = if (menu) 0.dp else 8.dp),
                        content = content,
                    )
                    if (actions != null) {
                        PaperActionLayout(Modifier.fillMaxWidth().padding(top = 14.dp), content = actions)
                    }
                }
            }
        }
    }
}

@Composable
fun PaperField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    placeholder: String = "",
    minLines: Int = 1,
    maxLines: Int = 8,
    transformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    sensitive: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        if (label.isNotBlank()) {
            Text(label, Modifier.padding(bottom = 8.dp), fontSize = 14.sp, color = PaperMuted)
        }
        BasicTextField(
            value,
            onValue,
            Modifier
                .fillMaxWidth()
                .background(PaperFill, RoundedCornerShape(8.dp))
                .heightIn(min = 50.dp)
                .semantics {
                    contentDescription = label
                    if (sensitive) password()
                }
                .padding(
                    start = 12.dp,
                    end = if (trailing == null) 12.dp else 4.dp,
                    top = if (trailing == null) 12.dp else 4.dp,
                    bottom = if (trailing == null) 12.dp else 4.dp,
                ),
            textStyle = TextStyle(
                fontFamily = FontFamily.SansSerif,
                color = PaperInk,
                fontSize = 18.sp,
                lineHeight = 29.sp,
            ),
            minLines = minLines,
            maxLines = maxLines,
            enabled = enabled,
            visualTransformation = transformation,
            keyboardOptions = keyboardOptions,
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, color = PaperMuted, fontSize = 18.sp)
                        inner()
                    }
                    trailing?.invoke(this)
                }
            },
        )
    }
}

@Composable
fun PaperAction(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = PaperInk,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val body: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(label, fontSize = 16.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal)
    }
    val framed = primary || LocalPaperFramedActions.current
    if (framed) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (color == PaperError) PaperError else PaperAccentStrong),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (primary) PaperAccent else Paper,
                contentColor = if (primary) PaperOnAccentColor else color,
                disabledContainerColor = PaperFill,
                disabledContentColor = PaperMuted,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            content = body,
        )
    } else {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = color),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            content = body,
        )
    }
}

/** Equal-width action row that wraps into a grid when the available width is narrow. */
@Composable
fun PaperActionLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val gap = 12.dp
    CompositionLocalProvider(LocalPaperFramedActions provides true) {
        Layout(
            content = content,
            modifier = modifier,
            measurePolicy = remember(gap) {
                object : MeasurePolicy {
                    override fun MeasureScope.measure(
                        measurables: List<androidx.compose.ui.layout.Measurable>,
                        constraints: Constraints,
                    ): MeasureResult {
                        val space = gap.roundToPx()
                        val width = if (constraints.hasBoundedWidth) {
                            constraints.maxWidth.coerceAtMost(560.dp.roundToPx())
                        } else {
                            560.dp.roundToPx()
                        }
                        if (measurables.isEmpty()) return layout(width, 0) {}
                        val minAction = 132.dp.roundToPx()
                        val columns = if (measurables.size == 1) {
                            1
                        } else {
                            ((width + space) / (minAction + space)).coerceIn(1, measurables.size)
                        }
                        val childWidth = ((width - space * (columns - 1)) / columns).coerceAtLeast(0)
                        val maxHeight = if (constraints.hasBoundedHeight) {
                            constraints.maxHeight
                        } else {
                            Constraints.Infinity
                        }
                        val children = measurables.map {
                            it.measure(
                                Constraints(
                                    minWidth = childWidth,
                                    maxWidth = childWidth,
                                    minHeight = 0,
                                    maxHeight = maxHeight,
                                ),
                            )
                        }
                        val rows = children.chunked(columns)
                        val height = rows.sumOf { row -> row.maxOf { it.height } } + space * (rows.size - 1)
                        return layout(width, height) {
                            var y = 0
                            rows.forEach { row ->
                                var x = 0
                                row.forEach { child ->
                                    child.placeRelative(x, y)
                                    x += childWidth + space
                                }
                                y += row.maxOf { it.height } + space
                            }
                        }
                    }

                    override fun IntrinsicMeasureScope.minIntrinsicWidth(
                        measurables: List<androidx.compose.ui.layout.IntrinsicMeasurable>,
                        height: Int,
                    ): Int = measurables.sumOf { it.minIntrinsicWidth(height) } + gap.roundToPx() * (measurables.size - 1).coerceAtLeast(0)

                    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
                        measurables: List<androidx.compose.ui.layout.IntrinsicMeasurable>,
                        height: Int,
                    ): Int = measurables.sumOf { it.maxIntrinsicWidth(height) } + gap.roundToPx() * (measurables.size - 1).coerceAtLeast(0)

                    override fun IntrinsicMeasureScope.minIntrinsicHeight(
                        measurables: List<androidx.compose.ui.layout.IntrinsicMeasurable>,
                        width: Int,
                    ): Int {
                        if (measurables.isEmpty()) return 0
                        val space = gap.roundToPx()
                        val bounded = width.coerceAtMost(560.dp.roundToPx())
                        val columns = if (measurables.size == 1) {
                            1
                        } else {
                            ((bounded + space) / (132.dp.roundToPx() + space)).coerceIn(1, measurables.size)
                        }
                        val childWidth = ((bounded - space * (columns - 1)) / columns).coerceAtLeast(0)
                        return measurables.maxOf { it.minIntrinsicHeight(childWidth) }
                    }

                    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
                        measurables: List<androidx.compose.ui.layout.IntrinsicMeasurable>,
                        width: Int,
                    ): Int {
                        if (measurables.isEmpty()) return 0
                        val space = gap.roundToPx()
                        val bounded = width.coerceAtMost(560.dp.roundToPx())
                        val columns = if (measurables.size == 1) {
                            1
                        } else {
                            ((bounded + space) / (132.dp.roundToPx() + space)).coerceIn(1, measurables.size)
                        }
                        val childWidth = ((bounded - space * (columns - 1)) / columns).coerceAtLeast(0)
                        return measurables.maxOf { it.maxIntrinsicHeight(childWidth) }
                    }
                }
            },
        )
    }
}

@Composable
fun PaperMenu(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    PaperSheet("", onDismiss, menu = true, content = content)

@Composable
fun PaperMenuAction(
    label: String,
    icon: ImageVector,
    color: Color = PaperInk,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = color)
        Spacer(Modifier.width(13.dp))
        Text(label, fontSize = 17.sp, color = color)
    }
}

@Composable
fun PaperChoiceMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .clearAndSetSemantics {}
            .background(if (selected) PaperAccent else Color.Transparent, RoundedCornerShape(4.dp))
            .border(
                1.dp,
                if (selected) PaperAccent else PaperMuted.copy(alpha = 0.55f),
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(15.dp), tint = PaperOnAccent)
    }
}

/** 60dp settings-style row: optional icon, title, optional subtitle and a trailing chevron. */
@Composable
fun PaperRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp), tint = PaperInk)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                color = if (enabled) PaperInk else PaperMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = PaperMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = PaperMuted)
    }
}

/** Full-screen paper page with a 60dp back/title bar and a scrolling body. */
@Composable
fun PaperPage(
    title: String,
    onBack: () -> Unit,
    secure: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view, secure) {
        val activity = view.context as? android.app.Activity
        val previous = activity?.window?.attributes?.flags ?: 0
        if (secure) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (secure && previous and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0) {
                activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = Paper, contentColor = PaperInk) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, "返回", Modifier.size(22.dp))
                }
                Text(
                    title,
                    Modifier.weight(1f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(48.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                content = content,
            )
            if (actions != null) {
                PaperActionLayout(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 18.dp),
                    content = actions,
                )
            }
        }
    }
}
