package com.nock.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Typing anywhere saves as you go; Done is how you finish. Fields call [saved] when they store something; Done (the
 * floating pill above the keyboard, or the keyboard's own Done key) closes the keyboard and, if anything was stored,
 * shows "Saved" with a tap of haptics.
 */
object Edits {
    private var dirty = false
    internal var flash by mutableIntStateOf(0)
    fun saved() { dirty = true }
    internal fun finish(): Boolean { val d = dirty; dirty = false; if (d) flash++; return d }
}

/** Keyboard options for a field that finishes with Done. */
fun doneKeys(number: Boolean = false) = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done)

/** The keyboard's Done key: same as the floating Done. */
@Composable
fun doneActions(): KeyboardActions {
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    return KeyboardActions(onDone = { focus.clearFocus(); if (Edits.finish()) haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
}

/** Floating Done pill above the keyboard while it's open, then a brief "Saved" confirmation. Put last in a full-screen Box. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.KeyboardDone() {
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val ime = WindowInsets.isImeVisible
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(Edits.flash) { if (Edits.flash > 0) { showSaved = true; delay(2200); showSaved = false } }
    val at = Modifier.align(Alignment.BottomEnd).imePadding().navigationBarsPadding().padding(16.dp)
    AnimatedVisibility(ime, at, enter = fadeIn(), exit = fadeOut()) {
        Text("Done", style = Type.Body, color = Nock.Bg, modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Nock.Fern)
            .clickable { focus.clearFocus(); if (Edits.finish()) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            .padding(horizontal = 22.dp, vertical = 12.dp))
    }
    AnimatedVisibility(showSaved && !ime, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp), enter = fadeIn(), exit = fadeOut()) {
        Text("Saved", style = Type.Body, color = Nock.Text, modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Nock.Surface2)
            .padding(horizontal = 22.dp, vertical = 12.dp))
    }
}
