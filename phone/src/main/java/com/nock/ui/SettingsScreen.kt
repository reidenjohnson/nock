package com.nock.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nock.data.Prefs

/**
 * Settings: grouped cards, label on the left, the control on the right. Every change saves itself.
 * [onBuzz] previews + sends the strength to the watch.
 */
@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit, onBuzz: (Int) -> Unit, backButton: @Composable (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(prefs.name) }
    var perEnd by remember { mutableIntStateOf(prefs.arrowsPerEnd) }
    var distance by remember { mutableIntStateOf(prefs.distanceYd) }
    var metric by remember { mutableStateOf(prefs.metric) }
    var buzz by remember { mutableIntStateOf(prefs.buzz) }
    var quality by remember { mutableIntStateOf(prefs.quality) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { overlay = Settings.canDrawOverlays(ctx) } // back from system settings

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            backButton(onBack)
            Text("Settings", style = Type.Title, color = Nock.Text)
        }
        item {
            Group("You") {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Name", style = Type.Body, color = Nock.Text, modifier = Modifier.weight(1f))
                    BasicTextField(
                        name, { name = it; prefs.name = it; Edits.saved() }, singleLine = true,
                        keyboardOptions = doneKeys(), keyboardActions = doneActions(),
                        textStyle = Type.Body.copy(color = Nock.Text, textAlign = TextAlign.End),
                        cursorBrush = SolidColor(Nock.Fern),
                        modifier = Modifier.width(180.dp),
                        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterEnd) { if (name.isEmpty()) Text("Your name", style = Type.Body, color = Nock.Faint); inner() } },
                    )
                }
            }
        }
        item {
            Group("Shooting") {
                Stepper("Arrows per end", perEnd, 1..12, "") { perEnd = it; prefs.arrowsPerEnd = it }
                Divider()
                Stepper("Distance to target", distance, 3..80, " yd") { distance = it; prefs.distanceYd = it }
                Divider()
                Choice("Units", listOf("Inches", "mm"), if (metric) 1 else 0) { metric = it == 1; prefs.metric = metric }
            }
        }
        item {
            Group("Watch") {
                Choice("Buzz", listOf("Light", "Medium", "Strong"), buzz) { buzz = it; prefs.buzz = it; onBuzz(it) }
                Divider()
                Choice("Live view", listOf("Fast", "Balanced", "Sharp"), quality) { quality = it; prefs.quality = it }
            }
        }
        item {
            Group("Permissions") {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Appear on top", style = Type.Body, color = Nock.Text)
                        Text("Lets your watch open Nock on this phone instantly.", style = Type.Small, color = Nock.Dim)
                    }
                    if (overlay) Text("On", style = Type.Body, color = Nock.Fern)
                    else Pill("Allow") { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(), style = Type.Label, color = Nock.Dim, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Nock.Surface).padding(horizontal = 18.dp)) { content() }
    }
}

@Composable private fun Divider() = HorizontalDivider(color = Nock.Line, thickness = 1.dp)

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Body, color = Nock.Text, modifier = Modifier.weight(1f))
        RoundButton(plus = false, value > range.first) { onChange(value - 1) }
        Text("$value$unit", style = Type.Number.copy(fontSize = Type.Number.fontSize * 0.8f), color = Nock.Text,
            textAlign = TextAlign.Center, modifier = Modifier.width(76.dp))
        RoundButton(plus = true, value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun RoundButton(plus: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Nock.Surface2).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        val c = if (enabled) Nock.Text else Nock.Faint
        if (plus) PlusIcon(c) else MinusIcon(c)
    }
}

/** Segmented choice: the picked one is a light pill. */
@Composable
private fun Choice(label: String, options: List<String>, picked: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, style = Type.Body, color = Nock.Text)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(Nock.Surface2).padding(4.dp)) {
            options.forEachIndexed { i, o ->
                val on = i == picked
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (on) Nock.Text else Nock.Surface2)
                        .clickable { onPick(i) }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(o, style = Type.Small, color = if (on) Nock.Bg else Nock.Text) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Nock.Fern).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = Type.Small, color = Nock.Bg)
    }
}
