package com.nock

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.nock.ui.KeyboardDone
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nock.data.Session
import com.nock.data.Shot
import com.nock.data.ShotStore
import com.nock.ui.AimTrace
import com.nock.ui.GroupPlot
import com.nock.ui.LogoMark
import com.nock.ui.Nock
import com.nock.ui.NockTheme
import com.nock.ui.TrendChart
import com.nock.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** Home: how you're shooting (hero, group, trend, aim) and one big way in to shoot. */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dir = File(filesDir, "shots")
        val prefs = com.nock.data.Prefs(this)
        metricUnits = prefs.metric
        org.opencv.android.OpenCVLoader.initLocal() // target sizes are measured with OpenCV
        val memory = TargetMemory(File(filesDir, "faces"))
        // A guest's arrows last a day.
        Thread { runCatching { ShotStore.purgeGuests(dir) }.getOrNull()?.takeIf { it > 0 }?.let { android.util.Log.d("NockShots", "guest arrows older than a day deleted: $it") } }.start()
        // Load every target for the camera now, in the background, so its first look doesn't wait for them.
        Thread { runCatching { TargetMemory.shared(File(filesDir, "faces")).warm() } }.apply { priority = Thread.MIN_PRIORITY }.start()
        setContent {
            NockTheme {
                var who by remember { mutableStateOf(prefs.active) }
                var people by remember { mutableStateOf(prefs.people) }
                var shots by remember(who) { mutableStateOf(ShotStore.load(dir, who)) }
                var picking by remember { mutableStateOf(false) }
                // Coming back from shooting: reload.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { shots = ShotStore.load(dir, who); metricUnits = prefs.metric; people = prefs.people }
                val sessions = remember(shots) { ShotStore.sessions(shots) }
                accent = accentFor(who, people)
                val pick: (String) -> Unit = { prefs.active = it; who = it }
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                var from by remember { mutableStateOf<Screen>(Screen.Home) } // where a shot was opened from
                BackHandler(enabled = screen != Screen.Home) {
                    screen = when (val s = screen) { is Screen.ShotView -> from; is Screen.TargetView -> Screen.Targets; else -> Screen.Home }
                }
                // After deleting or setting shots aside: reload, and stay on the same session if any of it is left.
                val reload: () -> Unit = {
                    shots = ShotStore.load(dir, who)
                    (screen as? Screen.SessionView)?.let { cur ->
                        val keep = cur.session.shots.map { it.json.path }.toSet()
                        screen = ShotStore.sessions(shots).firstOrNull { ss -> ss.shots.any { it.json.path in keep } }?.let { Screen.SessionView(it) } ?: Screen.Home
                    }
                }
                Box(Modifier.fillMaxSize().background(Nock.Bg)) {
                    AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { s ->
                        when (s) {
                            Screen.Home -> HomeScreen(sessions, remember(shots) { ShotStore.toCheck(dir, who) },
                                if (who == com.nock.data.Prefs.GUEST) "Guest" else people.firstOrNull { it.first == who }?.second ?: "", who == com.nock.data.Prefs.GUEST,
                                people = people, who = who, onSwitch = pick,
                                onPeople = { picking = true }, onShoot = ::shoot, onCheck = { review(toCheck = it) },
                                onSession = { screen = Screen.SessionView(it) }, onSettings = { screen = Screen.SettingsView },
                                onGallery = { screen = Screen.Gallery }, onTargets = { screen = Screen.Targets })
                            Screen.SettingsView -> com.nock.ui.SettingsScreen(prefs, onBack = { metricUnits = prefs.metric; people = prefs.people; screen = Screen.Home }, onBuzz = ::sendBuzz) { back -> BackRow(back) }
                            is Screen.SessionView -> SessionScreen2(s.session, onBack = { screen = Screen.Home }, onShot = { from = s; screen = Screen.ShotView(s.session, it) }, onChanged = reload)
                            is Screen.ShotView -> ShotScreen(s.shot, remember(shots) { FormBaseline.of(shots) }, onBack = { screen = from }, onFix = { review(at = s.shot.photo) })
                            Screen.Gallery -> GalleryScreen(sessions, onBack = { screen = Screen.Home }, onShot = { sh ->
                                from = Screen.Gallery; screen = Screen.ShotView(sessions.first { ss -> sh in ss.shots }, sh) }, onChanged = reload)
                            Screen.Targets -> TargetsScreen(memory, onBack = { screen = Screen.Home }, onOpen = { screen = Screen.TargetView(it.id) })
                            is Screen.TargetView -> TargetDetail(memory, s.id, onBack = { screen = Screen.Targets }, onAdd = { f ->
                                startActivity(Intent(this@HomeActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_ADD_VIEW, f.id)) })
                        }
                    }
                    if (!picking) KeyboardDone()
                    if (picking) PeoplePicker(people, who, onPick = { pick(it); picking = false },
                        onAdd = { name -> who = prefs.addPerson(name); people = prefs.people; picking = false },
                        onRemove = { id -> prefs.removePerson(id); people = prefs.people; who = prefs.active }, onDismiss = { picking = false })
                }
            }
        }
    }

    private fun shoot() = startActivity(Intent(this, MainActivity::class.java))

    /** Buzz strength lives on the watch; tell it (it buzzes once so you feel the new strength). */
    private fun sendBuzz(level: Int) {
        val mc = com.google.android.gms.wearable.Wearable.getMessageClient(this)
        com.google.android.gms.wearable.Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { mc.sendMessage(it.id, "/nock/buzz", byteArrayOf(level.toByte())) }
        }
    }
    private fun review(toCheck: Boolean = false, at: File? = null) = startActivity(
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_REVIEW, true)
            .putExtra(MainActivity.EXTRA_CHECK, toCheck).putExtra(MainActivity.EXTRA_PHOTO, at?.path)
    )
}

/**
 * Each person's own colour, so it's obvious at a glance whose Home this is: the first person Fern,
 * then Teal, Amber; a guest is Amber. Brick stays reserved for misses.
 */
private class Accent(val main: Color, val deep: Color, val deeper: Color)
private val ACCENTS = listOf(
    Accent(Nock.Fern, Nock.FernDeep, Nock.FernDeeper),
    Accent(Nock.Teal, Color(0xFF2F6573), Color(0xFF234C57)),
    Accent(Nock.Amber, Color(0xFF8A5A22), Color(0xFF6B4519)),
)
private fun accentFor(id: String, people: List<Pair<String, String>>): Accent =
    if (id == com.nock.data.Prefs.GUEST) ACCENTS[2] else ACCENTS[people.indexOfFirst { it.first == id }.coerceAtLeast(0) % ACCENTS.size]
private var accent by mutableStateOf(ACCENTS[0])

/** Units from Settings; every length on screen goes through len(). */
private var metricUnits by mutableStateOf(false)
private fun len(inches: Float) = com.nock.data.Prefs.len(inches, metricUnits)

private sealed interface Screen {
    data object Home : Screen
    data object SettingsView : Screen
    data object Gallery : Screen
    data object Targets : Screen
    data class TargetView(val id: String) : Screen
    data class SessionView(val session: Session) : Screen
    data class ShotView(val session: Session, val shot: Shot) : Screen
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeScreen(sessions: List<Session>, toCheck: Int, name: String, guest: Boolean,
                       people: List<Pair<String, String>>, who: String, onSwitch: (String) -> Unit, onPeople: () -> Unit, onShoot: () -> Unit, onCheck: (toCheck: Boolean) -> Unit, onSession: (Session) -> Unit, onSettings: () -> Unit,
                       onGallery: () -> Unit, onTargets: () -> Unit) {
    val last = sessions.firstOrNull()
    val lastMotion = sessions.asSequence().flatMap { it.shots.asReversed().asSequence() }.firstOrNull { it.motion != null }?.motion
    // Landscape: two columns of cards, so the wide screen is used instead of one long strip.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp > 600
    androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
        androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(if (wide) 2 else 1),
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalItemSpacing = 16.dp,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                LogoMark(30.dp)
                Spacer(Modifier.width(10.dp))
                Text("Nock", style = Type.Title, color = Nock.Text, modifier = Modifier.weight(1f))
                // Who's shooting: tap to switch or add someone.
                if (guest) {
                    // Guest: say so plainly, so nobody shoots a session into the wrong place.
                    Text("Guest", style = Type.Label, color = Nock.Amber,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Nock.Surface).clickable(onClick = onPeople).padding(horizontal = 14.dp, vertical = 11.dp))
                } else Box(Modifier.size(40.dp).clip(CircleShape).background(Nock.Surface).clickable(onClick = onPeople), contentAlignment = Alignment.Center) {
                    if (name.isBlank()) PersonIcon() else Text(name.trim().take(1).uppercase(), style = Type.Heading, color = accent.main)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).background(Nock.Surface).clickable(onClick = onSettings), contentAlignment = Alignment.Center) { Gear() }
            }
        }
        // More than one person: everyone's right here, one tap to swap.
        if (people.size > 1 || guest) item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) {
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val all = people + if (guest) listOf(com.nock.data.Prefs.GUEST to "Guest") else emptyList()
                for ((id, n) in all) {
                    val on = id == who
                    val a = accentFor(id, people)
                    Text(n.ifBlank { "You" }, style = Type.Label, color = if (on) Nock.Bg else Nock.Dim,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) a.main else Nock.Surface)
                            .clickable { onSwitch(id) }.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
        if (guest) item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) {
            Text("Guest arrows teach Nock and get their own report here. They never count toward anyone's stats.",
                style = Type.Small, color = Nock.Dim)
        }
        item { if (last != null) HeroCard(last, onClick = { onSession(last) }) else WelcomeCard() }
        item { ShootCard(onShoot) }
        if (sessions.isNotEmpty()) item { CheckCard(toCheck) { onCheck(toCheck > 0) } }
        // Your shots and your targets, one tap each.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((i, t) in listOf(Triple("All shots", "Select, set aside, delete", onGallery), Triple("Targets", "Names, sizes, what it learned", onTargets)).withIndex()) {
                    val (label, sub, go) = t
                    val tint = if (i == 0) accent.main else Nock.Teal // shots in your colour, targets in Teal
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(Nock.Surface).clickable(onClick = go).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(16.dp)) {
                                val w = 1.6.dp.toPx()
                                if (i == 0) { // a small grid of shots
                                    val g = size.width / 2 - 1.dp.toPx()
                                    for (x in 0..1) for (y in 0..1) drawRoundRect(tint, Offset(x * (g + 2.dp.toPx()), y * (g + 2.dp.toPx())),
                                        androidx.compose.ui.geometry.Size(g, g), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                                } else { // rings
                                    drawCircle(tint, size.minDimension / 2 - w, style = Stroke(w)); drawCircle(tint, size.minDimension / 4, style = Stroke(w))
                                    drawCircle(tint, 1.6.dp.toPx())
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(label, style = Type.Heading, color = Nock.Text)
                            Text(sub, style = Type.Small, color = Nock.Dim)
                        }
                    }
                }
            }
        }
        if (sessions.size >= 2) item {
            Card {
                CardHeader("How close", "Typical distance from your aim, by session. Lower is better.")
                TrendChart(sessions, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        item {
            Card {
                CardHeader("Your aim", "Your bow arm in the seconds before the shot. Red dot = release.")
                AimTrace(lastMotion, Modifier.fillMaxWidth().height(if (lastMotion == null) 72.dp else 220.dp).padding(top = 8.dp))
            }
        }
        if (sessions.isNotEmpty()) {
            item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) { Text("Sessions", style = Type.Heading, color = Nock.Text, modifier = Modifier.padding(top = 8.dp)) }
            sessions.forEach { s -> item { SessionRow(s) { onSession(s) } } }
        }
    }
}

@Composable
private fun HeroCard(s: Session, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1E2528), Nock.Surface)))
            .border(1.dp, Nock.Line, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick).padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(whenLabel(s.start).uppercase(Locale.US), style = Type.Label, color = Nock.Dim, modifier = Modifier.weight(1f))
            UnitToggle()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (metricUnits) "%.1f".format(s.medianIn * 25.4f) else "%.2f".format(s.medianIn), style = Type.Hero, color = Nock.Text)
                    Text(if (metricUnits) " mm" else "\"", style = Type.Title, color = Nock.Dim, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
                }
                Text("from your aim, typically", style = Type.Body, color = Nock.Dim)
            }
            GroupPlot(s.shots, Modifier.size(120.dp), dotDp = 2.5.dp)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat(score(s), "score")
            Stat(len(s.spreadIn), "group")
            Stat(len(s.best), "best")
            if (s.count >= 3) Stat(pull(s.biasRight, s.biasLow), "pull")
        }
    }
}

@Composable
private fun WelcomeCard() {
    Card {
        Text("Ready when you are", style = Type.Heading, color = Nock.Text)
        Spacer(Modifier.height(6.dp))
        Text("Set the phone by the target, wear your watch, and shoot. Every arrow is found and scored for you.", style = Type.Body, color = Nock.Dim)
    }
}

@Composable
private fun ShootCard(onShoot: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(accent.deep, accent.deeper)))
            .clickable(onClick = onShoot).padding(horizontal = 24.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Shoot", style = Type.Title, color = Color(0xFFF1F1EE))
            // A sleek arrow under the word: a line that fades in from nothing to a clean chevron tip.
            Canvas(Modifier.padding(top = 8.dp, bottom = 10.dp).width(120.dp).height(12.dp)) {
                val y = size.height / 2; val w = 2.dp.toPx(); val ink = Color(0xFFF1F1EE)
                drawLine(Brush.horizontalGradient(listOf(Color(0x00F1F1EE), ink), 0f, size.width), Offset(0f, y), Offset(size.width - 1.dp.toPx(), y), w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val t = Offset(size.width - 1.dp.toPx(), y); val d = 5.dp.toPx()
                drawLine(ink, t, Offset(t.x - d, y - d), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(ink, t, Offset(t.x - d, y + d), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Text("Camera on the target, watch on your wrist", style = Type.Small, color = Color(0xCCF1F1EE))
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x33F1F1EE)), contentAlignment = Alignment.Center) {
            Chevron(Color(0xFFF1F1EE))
        }
    }
}

/** A plain person glyph (head + shoulders) for when no name is set yet. */
@Composable
private fun PersonIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val w = size.width
        drawCircle(accent.main, radius = w * 0.22f, center = androidx.compose.ui.geometry.Offset(w / 2, w * 0.3f))
        drawArc(accent.main, 180f, 180f, useCenter = true,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, w * 0.62f), size = androidx.compose.ui.geometry.Size(w * 0.76f, w * 0.76f))
    }
}

/** Who's shooting: a pop-up over the dimmed Home. Pick a person, or add one by name. */
@Composable
private fun PeoplePicker(people: List<Pair<String, String>>, active: String, onPick: (String) -> Unit, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) } // hold a person to offer "Remove"
    var newName by remember { mutableStateOf("") }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 32.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Nock.Surface)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .padding(vertical = 12.dp),
        ) {
            Text("Who's shooting?", style = Type.Heading, color = Nock.Text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            for ((id, n) in people) {
                if (removing == id) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Remove ${n.ifEmpty { "them" }}? Arrows stay saved.", style = Type.Small, color = Nock.Dim, modifier = Modifier.weight(1f))
                        Text("Keep", style = Type.Body, color = Nock.Text, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { removing = null }.padding(10.dp))
                        Text("Remove", style = Type.Body, color = Nock.Brick, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { removing = null; onRemove(id) }.padding(10.dp))
                    }
                    continue
                }
                Row(
                    Modifier.fillMaxWidth()
                        .pointerInput(id) { detectTapGestures(onTap = { onPick(id) }, onLongPress = { if (id != com.nock.data.Prefs.FIRST) removing = id }) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Nock.Surface2), contentAlignment = Alignment.Center) {
                        if (n.isBlank()) PersonIcon() else Text(n.trim().take(1).uppercase(), style = Type.Body, color = Nock.Fern)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(n.ifEmpty { "You" }, style = Type.Body, color = Nock.Text, modifier = Modifier.weight(1f))
                    if (id == active) CheckMark()
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { onPick(com.nock.data.Prefs.GUEST) }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Nock.Surface2), contentAlignment = Alignment.Center) {
                    Text("G", style = Type.Body, color = Nock.Amber)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Guest", style = Type.Body, color = Nock.Text)
                    Text("Doesn't count toward your stats", style = Type.Small, color = Nock.Faint)
                }
                if (active == com.nock.data.Prefs.GUEST) CheckMark()
            }
            if (!adding) {
                Row(Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    com.nock.ui.PlusIcon(Nock.Teal)
                    Spacer(Modifier.width(10.dp))
                    Text("Add someone", style = Type.Body, color = Nock.Teal)
                }
            } else {
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.text.BasicTextField(
                        newName, { newName = it }, singleLine = true,
                        keyboardOptions = com.nock.ui.doneKeys(),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (newName.isNotBlank()) onAdd(newName.trim()) }),
                        textStyle = Type.Body.copy(color = Nock.Text),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Nock.Fern),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Nock.Surface2)
                            .padding(horizontal = 14.dp, vertical = 12.dp).focusRequester(focus),
                        decorationBox = { inner -> Box { if (newName.isEmpty()) Text("Name", style = Type.Body, color = Nock.Faint); inner() } },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Add", style = Type.Body, color = if (newName.isBlank()) Nock.Faint else Nock.Fern,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = newName.isNotBlank()) { onAdd(newName.trim()) }.padding(12.dp))
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
    }
}

/** Arrows you haven't fixed or confirmed: each one you check teaches the finder. Doubles as the way into every shot. */
@Composable
private fun CheckCard(toCheck: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nock.Surface).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (toCheck > 0) Nock.Amber else accent.main))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (toCheck > 0) "$toCheck ${if (toCheck == 1) "arrow" else "arrows"} to check" else "All arrows checked", style = Type.Heading, color = Nock.Text)
            Text(if (toCheck > 0) "Looks right, drag to fix, or not an arrow" else "Review every shot", style = Type.Small, color = Nock.Dim)
        }
        Chevron(Nock.Dim)
    }
}

@Composable
private fun SessionRow(s: Session, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nock.Surface).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupPlot(s.shots, Modifier.size(52.dp), dotDp = 1.5.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(whenLabel(s.start), style = Type.Body, color = Nock.Text)
            Text("${s.count} arrows · group ${len(s.spreadIn)}", style = Type.Small, color = Nock.Dim)
        }
        Text(len(s.medianIn), style = Type.Number, color = Nock.Text)
    }
}

// ---------------------------------------------------------------- Session

@Composable
private fun SessionScreen(s: Session, onBack: () -> Unit, onShot: (Shot) -> Unit) {
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Column {
                BackRow(onBack)
                Text(whenLabel(s.start), style = Type.Title, color = Nock.Text)
                Spacer(Modifier.height(16.dp))
                Card {
                    GroupPlot(s.shots, Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Stat(score(s), "score")
                        Stat(len(s.medianIn), "typical")
                        Stat(len(s.spreadIn), "group")
                        if (s.count >= 3) Stat(pull(s.biasRight, s.biasLow), "pull")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Arrows", style = Type.Heading, color = Nock.Text, modifier = Modifier.padding(top = 8.dp))
            }
        }
        items(s.shots.asReversed()) { shot -> ShotTile(shot) { onShot(shot) } }
    }
}

@Composable
private fun ShotTile(shot: Shot, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Nock.Surface).clickable(onClick = onClick)) {
        ShotPhoto(shot, Modifier.fillMaxWidth().aspectRatio(1f), halfIn = 1.6f)
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(len(shot.distIn), style = Type.Number.copy(fontSize = Type.Number.fontSize * 0.8f), color = Nock.Text, modifier = Modifier.weight(1f))
            RingBadge(shot.distIn)
            Spacer(Modifier.width(8.dp))
            Text("#${shot.index}", style = Type.Small, color = Nock.Faint)
        }
    }
}

/** The ring an arrow landed in: its score inside a border of that ring's colour. */
@Composable
private fun RingBadge(distIn: Float) {
    val col = com.nock.ui.Target.color(com.nock.ui.Target.score(distIn))
    Box(Modifier.size(26.dp).border(1.5.dp, col, CircleShape), contentAlignment = Alignment.Center) {
        Text(com.nock.ui.Target.label(distIn), style = Type.Label, color = col)
    }
}

// ---------------------------------------------------------------- Shot

/** Your normal, from your own shots: the median of each form measure. Verdicts wait until there are enough. */
private class FormBaseline(val hold: Float?, val last: Float?, val follow: Float?, val n: Int) {
    val ready get() = n >= MIN_SHOTS
    companion object {
        const val MIN_SHOTS = 8
        private fun med(v: List<Float>) = v.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        fun of(shots: List<Shot>): FormBaseline {
            val ms = shots.mapNotNull { it.motion }
            return FormBaseline(med(ms.map { it.holdDps }), med(ms.mapNotNull { it.lastDps }), med(ms.mapNotNull { it.followDps }), ms.count { it.lastDps != null })
        }
    }
}

/** 0 good, 1 okay, 2 off. Hold + last moment: less movement than your normal is better. Follow-through: like your normal is better. */
private fun grade(v: Float, normal: Float, lowerIsBetter: Boolean): Int {
    val r = v / normal
    return if (lowerIsBetter) when { r <= 1.25f -> 0; r <= 1.75f -> 1; else -> 2 }
    else kotlin.math.abs(r - 1f).let { when { it <= 0.3f -> 0; it <= 0.6f -> 1; else -> 2 } }
}
private val GRADE_COLORS = listOf(Nock.Fern, Nock.Amber, Nock.Brick)

/**
 * The shot's form vs your own normal: one verdict, then a bar per measure with a tick at your normal.
 * Bow-arm movement from the watch (deg/s): hold = last second of aim, last moment = the quarter second
 * before release, follow-through = the half second after.
 */
@Composable
private fun FormCard(m: com.nock.data.Motion, b: FormBaseline) {
    class Measure(val label: String, val v: Float, val normal: Float?, val lowerIsBetter: Boolean)
    val rows = listOfNotNull(
        Measure("Hold", m.holdDps, b.hold, true),
        m.lastDps?.let { Measure("Last moment", it, b.last, true) },
        m.followDps?.let { Measure("Follow-through", it, b.follow, false) },
    )
    val grades = rows.map { r -> if (b.ready && r.normal != null) grade(r.v, r.normal, r.lowerIsBetter) else -1 }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Form", style = Type.Heading, color = Nock.Text, modifier = Modifier.weight(1f))
            if (b.ready && grades.any { it >= 0 }) {
                val worst = grades.max()
                Text(listOf("Good", "Okay", "Off")[worst], style = Type.Label, color = Nock.Bg,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(GRADE_COLORS[worst]).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Text(if (b.ready) "This shot vs your normal (the tick)." else "Learning your normal: ${b.n} of ${FormBaseline.MIN_SHOTS} shots with the watch.",
            style = Type.Small, color = Nock.Dim)
        Spacer(Modifier.height(12.dp))
        rows.forEachIndexed { i, r ->
            val label = r.label; val v = r.v; val normal = r.normal
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Text(label, style = Type.Small, color = Nock.Text, modifier = Modifier.width(112.dp))
                val col = if (grades[i] >= 0) GRADE_COLORS[grades[i]] else Nock.Dim
                androidx.compose.foundation.Canvas(Modifier.weight(1f).height(18.dp)) {
                    val scale = (normal ?: v) * 2.5f
                    val y = size.height / 2; val h = 6.dp.toPx()
                    drawRoundRect(Nock.Surface2, topLeft = androidx.compose.ui.geometry.Offset(0f, y - h / 2),
                        size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
                    val x = (v / scale).coerceIn(0.03f, 1f) * size.width
                    drawRoundRect(col, topLeft = androidx.compose.ui.geometry.Offset(0f, y - h / 2),
                        size = androidx.compose.ui.geometry.Size(x, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
                    if (normal != null) {
                        val nx = (normal / scale) * size.width
                        drawLine(Nock.Text, androidx.compose.ui.geometry.Offset(nx, y - h * 1.3f), androidx.compose.ui.geometry.Offset(nx, y + h * 1.3f), 2.dp.toPx())
                    }
                }
                Text("%.1f°/s".format(v), style = Type.Small, color = Nock.Dim, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@Composable
private fun ShotScreen(shot: Shot, baseline: FormBaseline, onBack: () -> Unit, onFix: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) { BackRow(onBack); Spacer(Modifier.weight(1f)); UnitToggle() }
            Text("Arrow ${shot.index}", style = Type.Title, color = Nock.Text)
            Text(SimpleDateFormat("EEE MMM d · h:mm a", Locale.US).format(shot.timeMs), style = Type.Small, color = Nock.Dim)
        }
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp))) {
                ShotPhoto(shot, Modifier.fillMaxSize(), halfIn = 2.4f)
            }
        }
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (metricUnits) "%.1f".format(shot.distIn * 25.4f) else "%.2f".format(shot.distIn), style = Type.Hero, color = Nock.Text)
                Text(if (metricUnits) " mm" else "\"", style = Type.Title, color = Nock.Dim, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
                Spacer(Modifier.width(16.dp))
                Text(direction(shot.rightIn, shot.lowIn), style = Type.Body, color = Nock.Dim, modifier = Modifier.padding(bottom = 10.dp).weight(1f))
                Box(Modifier.padding(bottom = 8.dp)) { RingBadge(shot.distIn) }
            }
            Text(if (shot.fixed) "Placed by you" else "Found by Nock", style = Type.Label, color = if (shot.fixed) Nock.Text else Nock.Dim)
        }
        shot.motion?.let { m -> item { FormCard(m, baseline) } }
        item {
            Card {
                CardHeader("Your aim", "The seconds before this release.")
                AimTrace(shot.motion, Modifier.fillMaxWidth().height(if (shot.motion == null) 72.dp else 220.dp).padding(top = 8.dp))
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nock.Surface2).clickable(onClick = onFix).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Fix where it hit", style = Type.Body, color = Nock.Text) }
        }
    }
}

/** Close-up of the hit: spot-centred crop of the photo, + at the spot, ring at the hit. */
@Composable
private fun ShotPhoto(shot: Shot, modifier: Modifier, halfIn: Float) {
    val img by produceState<ImageBitmap?>(null, shot.photo, halfIn) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val half = (halfIn * shot.ppi).toInt()
                val dec = BitmapRegionDecoder.newInstance(shot.photo.path)
                val r = Rect((shot.spotX - half).toInt().coerceAtLeast(0), (shot.spotY - half).toInt().coerceAtLeast(0),
                    (shot.spotX + half).toInt().coerceAtMost(dec.width), (shot.spotY + half).toInt().coerceAtMost(dec.height))
                val opts = BitmapFactory.Options().apply { inSampleSize = if (half > 300) 2 else 1 }
                dec.decodeRegion(r, opts).also { dec.recycle() }.asImageBitmap() to r
            }.getOrNull()?.first
        }
    }
    Box(modifier.background(Nock.Surface2)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / (2 * halfIn * shot.ppi)
            val c = Offset(size.width / 2, size.height / 2)
            val arm = 9.dp.toPx()
            drawLine(Nock.Neon, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), 2.dp.toPx())
            drawLine(Nock.Neon, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), 2.dp.toPx())
            val h = Offset(c.x + (shot.entryX - shot.spotX) * k, c.y + (shot.entryY - shot.spotY) * k)
            drawCircle(if (shot.fixed) Nock.Text else Color(0xFFFFE600), radius = 10.dp.toPx(), center = h, style = Stroke(1.75.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Nock.Surface).padding(20.dp)) { content() }
}

@Composable
private fun CardHeader(title: String, sub: String) {
    Text(title, style = Type.Heading, color = Nock.Text)
    Text(sub, style = Type.Small, color = Nock.Dim, modifier = Modifier.padding(top = 2.dp))
}

/** Score on the standard 40 cm face, out of 10 per arrow: "56/60". */
private fun score(s: Session) = "${s.shots.sumOf { com.nock.ui.Target.score(it.distIn) }}/${s.count * 10}"

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = Type.Number.copy(fontSize = Type.Number.fontSize * 0.85f), color = Nock.Text)
        Text(label, style = Type.Label, color = Nock.Dim)
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Box(Modifier.padding(vertical = 8.dp).size(40.dp).clip(CircleShape).background(Nock.Surface).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) {
            drawLine(Nock.Text, Offset(size.width * 0.7f, 0f), Offset(size.width * 0.25f, size.height / 2), 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Nock.Text, Offset(size.width * 0.25f, size.height / 2), Offset(size.width * 0.7f, size.height), 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Settings gear: a solid toothed wheel with a hole. */
@Composable
private fun Gear() {
    Canvas(Modifier.size(20.dp)) {
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2
        for (i in 0 until 8) { // square-ish teeth
            val a = Math.toRadians(i * 45.0)
            val p1 = Offset(c.x + (r * 0.55f) * kotlin.math.cos(a).toFloat(), c.y + (r * 0.55f) * kotlin.math.sin(a).toFloat())
            val p2 = Offset(c.x + (r * 0.92f) * kotlin.math.cos(a).toFloat(), c.y + (r * 0.92f) * kotlin.math.sin(a).toFloat())
            drawLine(Nock.Text, p1, p2, r * 0.36f)
        }
        drawCircle(Nock.Text, radius = r * 0.66f, center = c)
        drawCircle(Nock.Surface, radius = r * 0.28f, center = c) // the hole
    }
}

/** A drawn check mark for the picked person. */
@Composable
private fun CheckMark() {
    Canvas(Modifier.size(20.dp)) {
        val w = 2.2.dp.toPx(); val s = size.width
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(s * 0.18f, s * 0.52f); lineTo(s * 0.42f, s * 0.74f); lineTo(s * 0.84f, s * 0.28f) },
            Nock.Fern, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun Chevron(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(14.dp)) {
        drawLine(color, Offset(size.width * 0.3f, 0f), Offset(size.width * 0.75f, size.height / 2), 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.75f, size.height / 2), Offset(size.width * 0.3f, size.height), 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private fun whenLabel(ms: Long): String {
    val now = java.util.Calendar.getInstance(); val t = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) "Today · " + SimpleDateFormat("h:mm a", Locale.US).format(ms) else SimpleDateFormat("EEE MMM d · h:mm a", Locale.US).format(ms)
}

/** Where the group sits vs the aim point, as a compass word (or "centred"). */
private fun pull(right: Float, low: Float): String {
    if (kotlin.math.hypot(right, low) < 0.15f) return "centred"
    return direction(right, low).substringBefore(" ")
}

private fun direction(right: Float, low: Float): String {
    val a = Math.toDegrees(kotlin.math.atan2(-low.toDouble(), right.toDouble()))
    val names = arrayOf("right", "high-right", "high", "high-left", "left", "low-left", "low", "low-right")
    val name = names[(((a + 360 + 22.5) % 360) / 45).toInt()]
    return "$name · ${len(abs(right))} ${if (right >= 0) "right" else "left"}, ${len(abs(low))} ${if (low >= 0) "low" else "high"}"
}

// ---------------------------------------------------------------- Shots: select, delete, set aside

/** Inches / mm, everywhere at once. On every screen that shows a length. */
@Composable
private fun UnitToggle() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Nock.Surface).padding(3.dp)) {
        for ((label, metric) in listOf("in" to false, "mm" to true)) {
            val on = metricUnits == metric
            Text(label, style = Type.Label, color = if (on) Nock.Bg else Nock.Dim,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Nock.Text else Color.Transparent)
                    .clickable { metricUnits = metric; com.nock.data.Prefs(ctx).metric = metric }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/** A group of shots under a header (a round, or a session in the gallery). [onHoldHeader] = hold to delete the group. */
private class ShotGroup(val title: String, val sub: String, val shots: List<Shot>)

/**
 * Small square tiles, four across. Tap opens the arrow; hold a tile to start selecting, then tap to add more.
 * Selecting shows a bar: Not an arrow (kept, left out of everything) or Delete (gone for good, confirmed).
 */
@Composable
private fun ShotGrid(
    header: @Composable () -> Unit,
    groups: List<ShotGroup>,
    onShot: (Shot) -> Unit,
    onChanged: () -> Unit,
    onDeleteGroup: ((ShotGroup) -> Unit)? = null,
) {
    var selected by remember(groups) { mutableStateOf(setOf<Shot>()) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val selecting = selected.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(92.dp), // small tiles: as many across as fit, portrait or landscape
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
            for (g in groups) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)
                            .pointerInput(g) { detectTapGestures(onLongPress = { if (onDeleteGroup != null) confirm = "Delete ${g.title.lowercase()}? ${g.shots.size} ${if (g.shots.size == 1) "arrow" else "arrows"}, for good." to { onDeleteGroup(g) } }) },
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(g.title, style = Type.Heading, color = Nock.Text, modifier = Modifier.weight(1f))
                        Text(g.sub, style = Type.Small, color = accent.main) // the round's score in your colour
                    }
                }
                items(g.shots.asReversed(), key = { it.json.path }) { shot ->
                    val on = shot in selected
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                            .border(if (on) 2.dp else 0.dp, if (on) Nock.Text else Color.Transparent, RoundedCornerShape(14.dp))
                            .pointerInput(shot, selecting) {
                                detectTapGestures(
                                    onTap = { if (selecting) selected = if (on) selected - shot else selected + shot else onShot(shot) },
                                    onLongPress = { selected = selected + shot },
                                )
                            },
                    ) {
                        ShotPhoto(shot, Modifier.fillMaxSize(), halfIn = 1.2f)
                        Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Color(0xB3202321)), contentAlignment = Alignment.Center) {
                            Text(com.nock.ui.Target.label(shot.distIn), style = Type.Label.copy(fontSize = Type.Label.fontSize * 0.8f),
                                color = com.nock.ui.Target.color(com.nock.ui.Target.score(shot.distIn)))
                        }
                        if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).clip(CircleShape)
                            .background(if (on) Nock.Text else Color(0x66202321)).border(1.5.dp, Nock.Text, CircleShape))
                    }
                }
            }
        }
        // The selection bar, floating at the bottom.
        if (selecting) Row(
            Modifier.align(Alignment.BottomCenter).padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Nock.Surface2).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${selected.size} selected", style = Type.Body, color = Nock.Text, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text("Cancel", style = Type.Label, color = Nock.Dim, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { selected = emptySet() }.padding(12.dp))
            Text("Not an arrow", style = Type.Label, color = Nock.Text, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                ShotStore.ignore(selected); selected = emptySet(); onChanged()
            }.padding(12.dp))
            Text("Delete", style = Type.Label, color = Nock.Brick, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                val n = selected.size
                confirm = "Delete $n ${if (n == 1) "arrow" else "arrows"} for good?" to { ShotStore.delete(selected); selected = emptySet(); onChanged() }
            }.padding(12.dp))
        }
        confirm?.let { (q, act) -> ConfirmPop(q, "Delete", onYes = { act(); confirm = null }, onNo = { confirm = null }) }
    }
}

/** A yes/no pop-up over a dimmed screen. */
@Composable
private fun ConfirmPop(question: String, yes: String, onYes: () -> Unit, onNo: () -> Unit) {
    BackHandler(onBack = onNo)
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onNo), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).clip(RoundedCornerShape(24.dp)).background(Nock.Surface).padding(22.dp)) {
            Text(question, style = Type.Body, color = Nock.Text)
            Row(Modifier.padding(top = 18.dp).align(Alignment.End)) {
                Text("Keep", style = Type.Label, color = Nock.Text, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onNo).padding(12.dp))
                Spacer(Modifier.width(8.dp))
                Text(yes, style = Type.Label, color = Nock.Brick, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onYes).padding(12.dp))
            }
        }
    }
}

@Composable
private fun ScreenTitle(title: String, sub: String?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackRow(onBack)
        Spacer(Modifier.weight(1f))
        UnitToggle()
    }
    Text(title, style = Type.Title, color = Nock.Text)
    sub?.let { Text(it, style = Type.Small, color = Nock.Dim) }
}

/** One session: the zoomable group (tap a dot to open it), the numbers, then each round as its own block. */
@Composable
private fun SessionScreen2(s0: Session, onBack: () -> Unit, onShot: (Shot) -> Unit, onChanged: () -> Unit) {
    // Shot from a known distance: see the group as shot, or estimated at another distance (same aim angle).
    val shotYd = remember(s0) { s0.shots.map { it.yd }.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
    var showYd by remember(s0) { mutableStateOf(shotYd ?: 0) }
    val s = remember(s0, showYd) { if (shotYd == null || showYd == shotYd) s0 else Session(s0.shots.map { it.at(showYd) }) }
    val rounds = remember(s) { ShotStore.rounds(s) }
    val groups = rounds.mapIndexed { i, r ->
        val pts = r.sumOf { com.nock.ui.Target.score(it.distIn) }
        ShotGroup("Round ${i + 1}", "$pts/${r.size * 10} · ${r.size} ${if (r.size == 1) "arrow" else "arrows"}", r)
    }.asReversed()
    ShotGrid(
        header = {
            Column {
                ScreenTitle(whenLabel(s.start), "Pinch the target to zoom · tap a dot to open it · hold a round to delete it", onBack)
                Spacer(Modifier.height(14.dp))
                if (shotYd != null) {
                    Row(Modifier.padding(bottom = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (y in (listOf(shotYd) + listOf(10, 20, 30, 40, 50, 70)).distinct()) {
                            val on = y == showYd
                            Text(if (y == shotYd) "$y yd · shot" else "$y yd", style = Type.Label, color = if (on) Nock.Bg else Nock.Text,
                                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Nock.Text else Nock.Surface2).clickable { showYd = y }
                                    .padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                    if (showYd != shotYd) Text("Estimate: your $shotYd yd group at $showYd yd, if your aim's angle stays the same.", style = Type.Small, color = Nock.Dim, modifier = Modifier.padding(bottom = 8.dp))
                }
                Card {
                    // The target at a sensible size; numbers beside it on a wide screen, under it on a tall one.
                    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp > 600
                    val plot = @Composable { com.nock.ui.GroupPlotInteractive(s.shots, Modifier.size(if (wide) 300.dp else 320.dp), onShot) }
                    val stats = @Composable {
                        Stat(score(s), "score"); Stat(len(s.medianIn), "typical"); Stat(len(s.spreadIn), "group")
                        if (s.count >= 3) Stat(pull(s.biasRight, s.biasLow), "pull")
                    }
                    if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
                        plot(); Spacer(Modifier.width(32.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { stats() }
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        plot(); Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { stats() }
                    }
                }
            }
        },
        groups = groups, onShot = onShot, onChanged = onChanged,
        onDeleteGroup = { g -> ShotStore.delete(g.shots); onChanged() },
    )
}

/** Every arrow, newest session first, to clean up after testing: select many, set aside or delete. */
@Composable
private fun GalleryScreen(sessions: List<Session>, onBack: () -> Unit, onShot: (Shot) -> Unit, onChanged: () -> Unit) {
    ShotGrid(
        header = { Column { ScreenTitle("All shots", "Hold an arrow to select · hold a session to delete it", onBack) } },
        groups = sessions.map { ShotGroup(whenLabel(it.start), "${it.count} ${if (it.count == 1) "arrow" else "arrows"}", it.shots) },
        onShot = onShot, onChanged = onChanged,
        onDeleteGroup = { g -> ShotStore.delete(g.shots); onChanged() },
    )
}

// ---------------------------------------------------------------- Targets

/** Your saved targets: rename and fix sizes right here (type, it saves), see the bullseyes, hold to delete. */
@Composable
private fun TargetsScreen(memory: TargetMemory, onBack: () -> Unit, onOpen: (TargetMemory.Face) -> Unit) {
    var faces by remember { mutableStateOf(memory.faces()) }
    var confirm by remember { mutableStateOf<TargetMemory.Face?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ScreenTitle("Targets", "Tap one to see everything it learned. Hold to delete. Mark new ones from the camera.", onBack) }
            if (faces.isEmpty()) item { Text("No targets yet. Point the camera at one and tap Mark target.", style = Type.Body, color = Nock.Dim) }
            items(faces, key = { it.id }) { f -> TargetCard(memory, f, onChanged = { faces = memory.faces() }, onHold = { confirm = f }, onTap = { onOpen(f) }) }
        }
        confirm?.let { f -> ConfirmPop("Delete ${f.name}? Its marks and everything it learned go too.", "Delete",
            onYes = { memory.delete(f); faces = memory.faces(); confirm = null }, onNo = { confirm = null }) }
    }
}

/**
 * A target, the same as its menu on the camera: the marked photo (outline, numbered bullseyes, centres) and beside it
 * the name and size (type to fix, saves itself) and each bullseye's size and where its centre sits.
 */
@Composable
private fun TargetCard(memory: TargetMemory, face: TargetMemory.Face, onChanged: () -> Unit, onHold: () -> Unit, onTap: (() -> Unit)? = null) {
    var f by remember(face.id) { mutableStateOf(face) }
    val facts = remember(f.widthIn, f.heightIn, f.bulls, metricUnits) { MarkedImage.facts(memory, f, metricUnits) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val marked by produceState<ImageBitmap?>(null, f.photo, f.outline, f.bulls) {
        value = withContext(Dispatchers.IO) { runCatching { MarkedImage.render(ctx, memory, f, f.photo, metricUnits, 1000) }.getOrNull()?.asImageBitmap() }
    }
    val photo = marked ?: rememberCut(memory, f, f.photo, 4)
    fun fmt(v: Double) = if (metricUnits) "%.1f".format(v * 25.4) else "%.2f".format(v)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Nock.Surface)
        .pointerInput(f.id) { detectTapGestures(onTap = { onTap?.invoke() }, onLongPress = { onHold() }) }) {
        val wide = maxWidth > 560.dp
        val pic = @Composable { m: Modifier ->
            if (wide) Box(m.height(280.dp)) {
                photo?.let { Image(it, null, Modifier.align(Alignment.Center).aspectRatio(it.width.toFloat() / it.height).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit) }
            } else {
                // portrait: the photo runs edge to edge across the top of the card (no grey bars beside it); a tall one is
                // only trimmed in the room around the target (at most 10:9 tall)
                val r = photo?.let { (it.width.toFloat() / it.height).coerceAtLeast(0.9f) } ?: 1.2f
                Box(m.aspectRatio(r).background(Nock.Surface2)) {
                    photo?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
        }
        val details = @Composable { m: Modifier ->
            Column(m) {
                var name by remember(f.id) { mutableStateOf(f.name) }
                androidx.compose.foundation.text.BasicTextField(name, { name = it; if (it.isNotBlank()) { f = memory.rename(f, it.trim()); com.nock.ui.Edits.saved() } },
                    singleLine = true, textStyle = Type.Title.copy(color = Nock.Text), cursorBrush = androidx.compose.ui.graphics.SolidColor(accent.main),
                    keyboardOptions = com.nock.ui.doneKeys(), keyboardActions = com.nock.ui.doneActions(),
                    modifier = Modifier.onFocusChanged { if (!it.isFocused && name.isBlank()) name = f.name }) // blank isn't saved: put the name back
                if (f.marked) {
                    // one line (the photo shows the rest); tap it for the sizes and each bullseye, in place
                    var open by remember(f.id) { mutableStateOf(false) }
                    fun n(v: Double) = if (metricUnits) "%.0f".format(v * 25.4) else if (v % 1.0 == 0.0) "%.0f".format(v) else "%.2f".format(v)
                    val b = f.bulls.size
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).clickable { open = !open }.padding(vertical = 6.dp)) {
                        Text(n(f.widthIn) + " × " + n(f.heightIn) + (if (metricUnits) " mm" else " in") + if (b > 0) " · $b " + (if (b == 1) "bullseye" else "bullseyes") else "",
                            style = Type.Body, color = Nock.Dim, modifier = Modifier.weight(1f))
                        Text(if (open) "Less" else "More", style = Type.Small, color = Nock.Dim)
                    }
                    if (open) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Text("Size", style = Type.Body, color = Nock.Dim, modifier = Modifier.weight(1f))
                        SizeField(fmt(f.widthIn)) { v -> v?.let { f = memory.resize(f, if (metricUnits) it / 25.4 else it, f.heightIn); onChanged() } }
                        Text("  ×  ", style = Type.Body, color = Nock.Dim)
                        SizeField(fmt(f.heightIn)) { v -> v?.let { f = memory.resize(f, f.widthIn, if (metricUnits) it / 25.4 else it); onChanged() } }
                        Text(if (metricUnits) "  mm" else "  in", style = Type.Small, color = Nock.Dim)
                    }
                    facts.forEach { x ->
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                            Text(x.label, style = Type.Body, color = Nock.Dim, modifier = Modifier.weight(1f))
                            Text(x.value, style = Type.Heading, color = Nock.Text)
                        }
                        Text(x.where, style = Type.Small, color = Nock.Dim, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                    }
                    if (facts.isNotEmpty()) Text(MarkedImage.ESTIMATED, style = Type.Small, color = Nock.Dim, modifier = Modifier.padding(top = 12.dp))
                    }
                } else Text("Tape spots only · mark it from the camera for true inches", style = Type.Small, color = Nock.Dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (wide) Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { pic(Modifier.weight(1f)); Spacer(Modifier.width(18.dp)); details(Modifier.weight(1f)) }
        else Column { pic(Modifier.fillMaxWidth()); details(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp)) }
    }
}

/**
 * The target cut out of one of its images, flat and face-on (no room around it). Falls back to the plain photo
 * ([sample] = its decode size) when the image has no outline mapping.
 */
@Composable
private fun rememberCut(memory: TargetMemory, f: TargetMemory.Face, file: java.io.File, sample: Int): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, file, f.outline, f.widthIn, f.heightIn) {
        value = withContext(Dispatchers.IO) {
            runCatching { memory.cutout(f, file) }.getOrNull()?.asImageBitmap()
                ?: runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull()
        }
    }
    return img
}

/** Thumbnail shape = the target's own shape (so the cut fills it, no bars), kept between tall and wide limits. */
private fun cutAspect(f: TargetMemory.Face, img: ImageBitmap?): Float =
    (img?.let { it.width.toFloat() / it.height } ?: if (f.marked) (f.widthIn / f.heightIn).toFloat() else 1f).coerceIn(0.6f, 1.6f)

/** A typeable number in a pill; a valid number saves itself. Leaving the field shows the saved value again. */
@Composable
private fun SizeField(initial: String, onValue: (Double?) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(initial, editing) { if (!editing) t = initial } // e.g. the units changed; never while you type
    androidx.compose.foundation.text.BasicTextField(t, { t = it; it.toDoubleOrNull()?.takeIf { v -> v > 0 }?.let { v -> onValue(v); com.nock.ui.Edits.saved() } },
        singleLine = true, textStyle = Type.Body.copy(color = Nock.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        keyboardOptions = com.nock.ui.doneKeys(number = true), keyboardActions = com.nock.ui.doneActions(),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(accent.main),
        modifier = Modifier.width(64.dp).clip(RoundedCornerShape(14.dp)).background(Nock.Surface2).padding(vertical = 8.dp)
            .onFocusChanged { editing = it.isFocused })
}


/**
 * One target: its name and size (type to fix), then every image that taught Nock to see it: the main photo you marked,
 * the views it learned, and the ones you added. Hold an image to remove it. Add views takes you to the camera.
 */
@Composable
private fun TargetDetail(memory: TargetMemory, id: String, onBack: () -> Unit, onAdd: (TargetMemory.Face) -> Unit) {
    var face by remember { mutableStateOf(memory.faces().firstOrNull { it.id == id }) }
    val f = face ?: run { onBack(); return }
    var images by remember(f.id) { mutableStateOf(memory.images(f)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { face = memory.faces().firstOrNull { it.id == id }; face?.let { images = memory.images(it) } }
    var confirm by remember { mutableStateOf<TargetMemory.Image?>(null) }
    var viewing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(120.dp),
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) { BackRow(onBack); Spacer(Modifier.weight(1f)); UnitToggle() }
                    TargetCard(memory, f, onChanged = { face = memory.faces().firstOrNull { it.id == id } }, onHold = {}, onTap = { if (f.marked) viewing = true })
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("What Nock knows", style = Type.Heading, color = Nock.Text)
                            Text("${images.size} ${if (images.size == 1) "image" else "images"} · hold one to remove it", style = Type.Small, color = Nock.Dim)
                        }
                        Text("Add views", style = Type.Label, color = Nock.Bg, modifier = Modifier.clip(RoundedCornerShape(20.dp))
                            .background(accent.main).clickable { onAdd(f) }.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
            }
            items(images, key = { it.file.path }) { im ->
                val bmp = rememberCut(memory, f, im.file, 8)
                Box(Modifier.aspectRatio(cutAspect(f, bmp)).clip(RoundedCornerShape(16.dp)).background(Nock.Surface2)
                    .pointerInput(im) { detectTapGestures(onLongPress = { if (!im.main) confirm = im }) }) {
                    bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Text(when { im.main -> "Marked"; im.byHand -> "Added"; else -> "Learned" }, style = Type.Label, color = Nock.Text,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB3202321)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        confirm?.let { im -> ConfirmPop("Remove this image? Nock stops using it to recognise ${f.name}.", "Remove",
            onYes = { memory.deleteView(f, im.file); images = memory.images(f); confirm = null }, onNo = { confirm = null }) }
        if (viewing) MarkedViewer(memory, f) { viewing = false }
    }
}

/** A target's marked photo full screen, its sizes written on: pinch or double-tap to zoom, drag to look around, Done or Back closes. */
@Composable
private fun MarkedViewer(memory: TargetMemory, f: TargetMemory.Face, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val img by produceState<ImageBitmap?>(null, f.id, f.outline, f.bulls, f.widthIn, f.heightIn, metricUnits) {
        value = withContext(Dispatchers.IO) { runCatching { MarkedImage.render(ctx, memory, f, f.photo, metricUnits, 2400, sizes = true) }.getOrNull()?.asImageBitmap() }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val mid = androidx.compose.ui.geometry.Offset(w / 2, h / 2)
        // keep the picture on screen: at zoom k it can slide (k - 1) / 2 of the screen each way
        fun set(k: Float, o: androidx.compose.ui.geometry.Offset) {
            scale = k.coerceIn(1f, 6f)
            val mx = (scale - 1) * w / 2; val my = (scale - 1) * h / 2
            offset = androidx.compose.ui.geometry.Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        img?.let { bmp ->
            Image(bmp, f.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { p -> if (scale > 1.01f) set(1f, androidx.compose.ui.geometry.Offset.Zero) else set(2.5f, (p - mid) * (1 - 2.5f)) }) }
                .pointerInput(Unit) { detectTransformGestures { c, pan, zoom, _ ->
                    // the spot under your fingers stays under them
                    val k = (scale * zoom).coerceIn(1f, 6f); val z = k / scale
                    set(k, offset * z + (c - mid) * (1 - z) + pan)
                } }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
        }
        Text("Done", style = Type.Body, color = Nock.Bg, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
            .clip(RoundedCornerShape(22.dp)).background(Nock.Fern).clickable { onDone() }.padding(horizontal = 22.dp, vertical = 12.dp))
    }
}
