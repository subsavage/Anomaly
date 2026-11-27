package com.anomaly.game

import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anomaly.game.engine.*
import com.anomaly.game.audio.AudioCue
import com.anomaly.game.audio.AudioSettings
import com.anomaly.game.audio.GameAudioController
import com.anomaly.game.audio.ToneGameAudioController
import com.anomaly.game.narrative.DialogueEvent
import com.anomaly.game.narrative.DialogueMessage
import com.anomaly.game.narrative.EchoDialogueManager
import com.anomaly.game.narrative.EchoDialogueRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.random.Random

private val android.content.Context.dataStore by preferencesDataStore("anomaly_settings")
private val bestKey = intPreferencesKey("personal_best")
private val soundKey = booleanPreferencesKey("sound")
private val hapticKey = booleanPreferencesKey("haptics")
private val echoDialogueKey = booleanPreferencesKey("echo_dialogue")
private val subtitlesKey = booleanPreferencesKey("subtitles")
private val musicKey = booleanPreferencesKey("music")
private val briefingSeenKey = booleanPreferencesKey("briefing_seen")
private val soundEffectsKey = soundKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val barColor = android.graphics.Color.rgb(9, 13, 22)
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(barColor))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent { AnomalyApp() }
    }
}

private data class PlayerPreferences(
    val echoDialogue: Boolean = true,
    val subtitles: Boolean = true,
    val audio: AudioSettings = AudioSettings(),
    val haptics: Boolean = true
)

private class ArcadeModel(private val context: android.content.Context) : ViewModel() {
    private val engine = GameSessionEngine(Random.Default) { SystemClock.elapsedRealtime() }
    private val audio: GameAudioController = ToneGameAudioController()
    private val echoManager = EchoDialogueManager()
    private val _state = MutableStateFlow(engine.state)
    val state: StateFlow<GameState> = _state.asStateFlow()
    val best = context.dataStore.data.map { it[bestKey] ?: 0 }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val preferences = context.dataStore.data.map { prefs ->
        PlayerPreferences(
            echoDialogue = prefs[echoDialogueKey] ?: true,
            subtitles = prefs[subtitlesKey] ?: true,
            audio = AudioSettings(musicEnabled = prefs[musicKey] ?: true, soundEffectsEnabled = prefs[soundEffectsKey] ?: true),
            haptics = prefs[hapticKey] ?: true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerPreferences())
    private val _showBriefing = MutableStateFlow(false)
    val showBriefing: StateFlow<Boolean> = _showBriefing.asStateFlow()
    private val _echoMessage = MutableStateFlow<DialogueMessage?>(null)
    val echoMessage: StateFlow<DialogueMessage?> = _echoMessage.asStateFlow()
    private var briefingReturnsToSettings = false
    private var runStartedAt = 0L
    private var consecutiveMistakes = 0
    private var pendingTransitionAtTotal: Int? = null
    private var finalSecondsNotified = false
    private var unusualSignalNotified = false

    fun requestPlay() {
        viewModelScope.launch {
            val briefingSeen = context.dataStore.data.first()[briefingSeenKey] ?: false
            if (briefingSeen) startRun() else openBriefing(fromSettings = false)
        }
    }

    fun playAgain() = startRun()

    private fun startRun() {
        _showBriefing.value = false
        _state.value = engine.state.copy(settings = false, tutorialStep = 0)
        echoManager.beginRun()
        _echoMessage.value = null
        runStartedAt = SystemClock.elapsedRealtime()
        consecutiveMistakes = 0
        pendingTransitionAtTotal = null
        finalSecondsNotified = false
        unusualSignalNotified = false
        engine.start(best.value)
        _state.value = engine.state
        viewModelScope.launch {
            while (engine.state.running) {
                delay(50)
                val previous = engine.state
                engine.tick()
                val current = engine.state
                _state.value = current
                handleEngineUpdate(previous, current)
                if (current.running && !finalSecondsNotified && current.remainingMs <= 10_000L) {
                    finalSecondsNotified = sendDialogue(DialogueEvent.FINAL_SECONDS)
                }
            }
            if (engine.state.finished) persistBest()
        }
    }

    fun answer(anomaly: Boolean) {
        val previous = engine.state
        engine.answer(anomaly)
        val current = engine.state
        _state.value = current
        handleEngineUpdate(previous, current)
    }

    private fun handleEngineUpdate(previous: GameState, current: GameState) {
        if (current.stats.total <= previous.stats.total) return
        audio.updateSettings(preferences.value.audio)
        audio.playEffect(if (current.stats.correct > previous.stats.correct) AudioCue.CORRECT else AudioCue.INCORRECT)
        val round = previous.current
        val correct = current.stats.correct > previous.stats.correct

        val pendingTransition = pendingTransitionAtTotal
        if (pendingTransition != null && previous.stats.total >= pendingTransition) {
            if (correct) sendDialogue(DialogueEvent.TRANSITION_SURVIVED)
            pendingTransitionAtTotal = null
        }

        if (correct) {
            consecutiveMistakes = 0
            if (previous.stats.combo < 3 && current.stats.combo >= 3) sendDialogue(DialogueEvent.FIRST_STREAK)
            if (previous.stats.combo < 5 && current.stats.combo >= 5) sendDialogue(DialogueEvent.COMBO_MILESTONE)
            if (previous.stats.combo < 8 && current.stats.combo >= 8) sendDialogue(DialogueEvent.HIGH_PERFORMANCE)
            val subtleCatch = round?.isAnomaly == true && round.ruleAtSpawn.family == Family.ATTRIBUTE &&
                ((round.ruleAtSpawn.hue != null && round.ruleAtSpawn.dots != null) || previous.difficulty.subtlety > 0)
            if (subtleCatch) sendDialogue(DialogueEvent.SUBTLE_CATCH)
        } else {
            consecutiveMistakes++
            if (consecutiveMistakes == 2) sendDialogue(DialogueEvent.REPEATED_MISTAKES)
        }

        if (previous.integrity > 1 && current.integrity == 1) sendDialogue(DialogueEvent.LOW_INTEGRITY)
        if (current.stats.transitions > previous.stats.transitions) {
            pendingTransitionAtTotal = current.stats.total
            if (current.stats.transitions >= 3 && !unusualSignalNotified) {
                unusualSignalNotified = true
                sendDialogue(DialogueEvent.UNUSUAL_SYSTEM)
            } else {
                sendDialogue(DialogueEvent.PATTERN_TRANSITION)
            }
        }
        if (current.finished) persistBest()
    }

    private fun sendDialogue(event: DialogueEvent): Boolean {
        val prefs = preferences.value
        if (!prefs.echoDialogue || !prefs.subtitles) return false
        val elapsed = (SystemClock.elapsedRealtime() - runStartedAt).coerceAtLeast(0L)
        val message = echoManager.offer(event, elapsed, EchoDialogueRepository.initialOperation.id) ?: return false
        _echoMessage.value = message
        viewModelScope.launch {
            delay(message.durationMillis)
            if (_echoMessage.value?.id == message.id) {
                echoManager.dismiss(message.id)
                _echoMessage.value = null
            }
        }
        return true
    }

    private fun persistBest() {
        viewModelScope.launch {
            val newBest = engine.result(best.value).best
            context.dataStore.edit { it[bestKey] = newBest }
        }
    }

    val briefingButtonLabel: String get() = if (briefingReturnsToSettings) "RETURN TO SETTINGS" else "BEGIN OPERATION"

    fun openBriefing(fromSettings: Boolean) {
        briefingReturnsToSettings = fromSettings
        if (fromSettings) _state.value = _state.value.copy(settings = false)
        _showBriefing.value = true
    }

    fun continueBriefing() {
        val returnToSettings = briefingReturnsToSettings
        briefingReturnsToSettings = false
        _showBriefing.value = false
        viewModelScope.launch {
            context.dataStore.edit { it[briefingSeenKey] = true }
            if (returnToSettings) _state.value = _state.value.copy(settings = true) else startRun()
        }
    }

    fun closeBriefing() {
        _showBriefing.value = false
        if (briefingReturnsToSettings) _state.value = _state.value.copy(settings = true)
        briefingReturnsToSettings = false
    }

    fun toggleSettings() { _state.value = _state.value.copy(settings = !_state.value.settings) }
    fun home() { _state.value = GameState(best = best.value) }
    fun tutorialComplete() { requestPlay() }
    fun tutorialAdvance() { _state.value = _state.value.copy(tutorialStep = _state.value.tutorialStep + 1) }
    fun skipTutorial() { _state.value = _state.value.copy(tutorialStep = 3) }

    fun setEchoDialogue(value: Boolean) { updatePreference(echoDialogueKey, value); if (!value) clearDialogue() }
    fun setSubtitles(value: Boolean) { updatePreference(subtitlesKey, value); if (!value) clearDialogue() }
    fun setMusic(value: Boolean) = updatePreference(musicKey, value)
    fun setSoundEffects(value: Boolean) = updatePreference(soundEffectsKey, value)
    fun setHaptics(value: Boolean) = updatePreference(hapticKey, value)
    private fun updatePreference(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        viewModelScope.launch { context.dataStore.edit { it[key] = value } }
    }
    private fun clearDialogue() { echoManager.beginRun(); _echoMessage.value = null }

    override fun onCleared() { audio.release(); super.onCleared() }
}
private val bg = Color(0xFF090D16); private val panel = Color(0xFF111927); private val muted = Color(0xFF8995A8); private val cyan = Color(0xFF54D7E8); private val coral = Color(0xFFFF626B); private val amber = Color(0xFFF4D64E); private val white = Color(0xFFEAF1FA); private val objectBlue = Color(0xFF4CA6FF); private val objectGreen = Color(0xFF55D18A)

@Composable private fun AnomalyApp() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val model: ArcadeModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ArcadeModel(context) as T })
    val state by model.state.collectAsState()
    val best by model.best.collectAsState()
    val preferences by model.preferences.collectAsState()
    val showBriefing by model.showBriefing.collectAsState()
    val echoMessage by model.echoMessage.collectAsState()
    val feedback = LocalHapticFeedback.current
    MaterialTheme { Surface(Modifier.fillMaxSize(), color = bg) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when {
                showBriefing -> MissionBriefingScreen(EchoDialogueRepository.initialOperation, model.briefingButtonLabel, model::continueBriefing, model::closeBriefing)
                state.settings -> SettingsScreen(
                    preferences,
                    model::setEchoDialogue,
                    model::setSubtitles,
                    model::setMusic,
                    model::setSoundEffects,
                    model::setHaptics,
                    { model.openBriefing(fromSettings = true) },
                    model::toggleSettings
                )
                state.running -> GameScreen(
                    state,
                    if (preferences.echoDialogue && preferences.subtitles) echoMessage else null,
                    onAnswer = { if (preferences.haptics) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.answer(it) }
                )
                state.finished -> ResultsScreen(model, state, best)
                state.tutorialStep in 1..2 -> TutorialScreen(state.tutorialStep, { if (state.tutorialStep == 2) model.tutorialComplete() else model.tutorialAdvance() }, model::skipTutorial)
                else -> HomeScreen(best, model::requestPlay, model::toggleSettings, model::tutorialAdvance)
            }
        }
    } }
}
@Composable private fun MissionBriefingScreen(operation: com.anomaly.game.narrative.OperationMetadata, beginLabel: String, begin: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("INCOMING TRANSMISSION", color = cyan, fontSize = 10.sp, letterSpacing = 2.sp)
            Text(operation.subtitle, color = muted, fontSize = 10.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 16.dp))
            Text(operation.title, color = white, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, modifier = Modifier.padding(top = 6.dp))
            Text(operation.objective, color = muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(22.dp))
            operation.briefing.forEachIndexed { index, line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(panel.copy(alpha = .78f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Text(if (index == 0) "ECHO" else "·", color = cyan, fontSize = if (index == 0) 9.sp else 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(end = 10.dp, top = 2.dp))
                    Text(line, color = white, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
        Column {
            BigButton(beginLabel, cyan, begin)
            Text("BACK", color = muted, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.align(Alignment.CenterHorizontally).clickable(onClick = back).padding(16.dp))
        }
    }
}
@Composable private fun HomeScreen(best: Int, play: () -> Unit, settings: () -> Unit, tutorial: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text("⚙", color = muted, fontSize = 24.sp, modifier = Modifier.clickable(onClick = settings).padding(8.dp)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(150.dp)) { drawCircle(cyan.copy(alpha = .9f), radius = size.minDimension * .34f, style = Stroke(3.dp.toPx())); drawRect(cyan.copy(alpha = .26f), topLeft = Offset(size.width*.28f,size.height*.28f), size = Size(size.width*.44f,size.height*.44f), style = Stroke(3.dp.toPx())); val path=Path().apply { moveTo(size.width*.5f,size.height*.08f); lineTo(size.width*.88f,size.height*.8f); lineTo(size.width*.12f,size.height*.8f); close() }; drawPath(path, cyan.copy(alpha=.32f), style=Stroke(3.dp.toPx())) }
            Spacer(Modifier.height(24.dp)); Text("ANOMALY", color=white, fontSize=34.sp, fontWeight=FontWeight.Black, letterSpacing=5.sp); Text("READ THE SIGNAL. FIND THE BREAK.", color=muted, fontSize=11.sp, letterSpacing=2.sp, modifier=Modifier.padding(top=10.dp))
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            if (best > 0) Text("PERSONAL BEST     ${best}", color=amber, fontSize=12.sp, letterSpacing=1.5.sp, modifier=Modifier.padding(bottom=22.dp))
            BigButton("PLAY", cyan, play)
            Text("TUTORIAL", color=muted, fontSize=12.sp, letterSpacing=2.sp, modifier=Modifier.clickable(onClick=tutorial).padding(22.dp))
        }
    }
}

@Composable private fun GameScreen(s: GameState, echoMessage: DialogueMessage?, onAnswer: (Boolean)->Unit) {
    val round=s.current; val rule=round?.ruleAtSpawn ?: s.rule
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp, vertical=12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween) {
            Column { Text("SCORE", color=muted, fontSize=10.sp, letterSpacing=1.5.sp); Text("${s.stats.score}", color=white, fontSize=24.sp, fontWeight=FontWeight.Bold) }
            Column(horizontalAlignment=Alignment.CenterHorizontally) { Text("${(s.remainingMs/1000f).let { String.format("%.1f",it) }}", color=if(s.remainingMs<10000)coral else white, fontSize=26.sp, fontWeight=FontWeight.Bold); Text("SEC", color=muted, fontSize=9.sp) }
            Row { repeat(3) { i -> Text(if (i < s.integrity) "◆" else "◇", color=if(i<s.integrity)coral else muted, fontSize=17.sp, modifier=Modifier.padding(2.dp)) } }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).padding(horizontal=14.dp, vertical=12.dp)) {
            Text("ACTIVE RULE", color=muted, fontSize=9.sp, letterSpacing=1.7.sp)
            Text(rule?.playerInstruction(s.history) ?: "Calibrating…", color=cyan, fontSize=14.sp, lineHeight=18.sp, fontWeight=FontWeight.SemiBold, maxLines=3, modifier=Modifier.padding(top=4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top=12.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
            Text("RECENT", color=muted, fontSize=9.sp, letterSpacing=1.5.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) { s.history.takeLast(5).forEach { ObjectGlyph(it, Modifier.size(26.dp)) } }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF0D1522), bg)), RoundedCornerShape(24.dp)), contentAlignment=Alignment.Center) {
            if(round!=null) ObjectGlyph(round.item, Modifier.size(190.dp), glow=true)
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal=12.dp, vertical=12.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                when (s.message) {
                    "SYSTEM ALERT · PATTERN SHIFT NEXT" -> Text("PATTERN SHIFT NEXT", color=amber, fontSize=10.sp, fontWeight=FontWeight.Black, letterSpacing=1.7.sp, modifier=Modifier.background(amber.copy(alpha=.12f), RoundedCornerShape(20.dp)).padding(horizontal=13.dp, vertical=7.dp))
                    "PATTERN SHIFT" -> Text("RULE UPDATED", color=amber, fontSize=10.sp, fontWeight=FontWeight.Black, letterSpacing=1.7.sp, modifier=Modifier.background(amber.copy(alpha=.16f), RoundedCornerShape(20.dp)).padding(horizontal=13.dp, vertical=7.dp))
                    else -> if(s.message.isNotBlank()) Text(s.message, color=if(s.lastCorrect==false)coral else amber, fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=2.sp)
                }
                Text(rule?.focusCue(s.history) ?: "READ THE ACTIVE RULE", color=cyan, fontSize=14.sp, fontWeight=FontWeight.Bold, textAlign=TextAlign.Center, maxLines=2, modifier=Modifier.padding(top=6.dp))
                if(s.stats.combo>=3) Text("${s.stats.combo}× COMBO", color=muted, fontSize=10.sp, letterSpacing=1.6.sp, modifier=Modifier.padding(top=4.dp))
            }
        }
        EchoDialogueOverlay(echoMessage)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            DecisionZone("NORMAL", "FOLLOWS THE RULE", cyan, Modifier.weight(1f), onClick={onAnswer(false)})
            DecisionZone("ANOMALY", "BREAKS THE RULE", coral, Modifier.weight(1f), onClick={onAnswer(true)})
        }
    }
}

@Composable private fun EchoDialogueOverlay(message: DialogueMessage?) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
    ) {
        val current = message
        if (current != null) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xD90B111C), RoundedCornerShape(12.dp))
                    .border(1.dp, cyan.copy(alpha = .2f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).background(cyan, RoundedCornerShape(50)))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("ECHO  ·  SECURE CHANNEL", color = cyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Text(current.text, color = white, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable private fun DecisionZone(title:String, sub:String, color:Color, modifier:Modifier, onClick:()->Unit) {
    Column(modifier.fillMaxHeight().background(color.copy(alpha=.09f), RoundedCornerShape(18.dp)).border(1.dp,color.copy(alpha=.42f),RoundedCornerShape(18.dp)).clickable(onClick=onClick).padding(12.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
        Text(title,color=color,fontSize=19.sp,fontWeight=FontWeight.Black,letterSpacing=1.5.sp); Text(sub,color=muted,fontSize=8.sp,letterSpacing=1.sp,modifier=Modifier.padding(top=5.dp))
    }
}

@Composable private fun ObjectGlyph(item: GameObject, modifier: Modifier, glow:Boolean=false) {
    val color=when(item.hue){Hue.CORAL->coral;Hue.BLUE->objectBlue;Hue.YELLOW->amber;Hue.GREEN->objectGreen}
    Canvas(modifier) {
        val unit=size.minDimension*.52f; val left=(size.width-unit)/2; val top=(size.height-unit)/2; val center=Offset(size.width/2,size.height/2)
        if(glow) drawCircle(color.copy(alpha=.12f),unit*.84f,center)
        when(item.shape){
            Shape.CIRCLE->drawCircle(color,unit*.48f,center)
            Shape.SQUARE->drawRoundRect(color,Offset(left,top),Size(unit,unit),androidx.compose.ui.geometry.CornerRadius(unit*.09f))
            Shape.TRIANGLE->{val p=Path().apply{moveTo(center.x,top);lineTo(left+unit,top+unit*.88f);lineTo(left,top+unit*.88f);close()};drawPath(p,color)}
        }
        val count=item.dots.coerceIn(0,3); val dot=size.minDimension*.042f
        if(count>0){val positions=when(count){1->listOf(Offset(center.x,center.y));2->listOf(Offset(center.x-dot*1.8f,center.y),Offset(center.x+dot*1.8f,center.y));else->listOf(Offset(center.x,center.y-dot*1.8f),Offset(center.x-dot*1.7f,center.y+dot*1.2f),Offset(center.x+dot*1.7f,center.y+dot*1.2f))}; positions.forEach{drawCircle(bg,dot,it)}}
    }
}

@Composable private fun TutorialScreen(step:Int, next:()->Unit, skip:()->Unit) {
    val rule=if(step==1) "REPEATING ORDER: CIRCLE → SQUARE → TRIANGLE" else "AFTER CIRCLE, EXPECT SQUARE"
    val targetAnomaly=step==2
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End){Text("SKIP",color=muted,modifier=Modifier.clickable(onClick=skip).padding(12.dp))}
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text("QUICK CALIBRATION",color=cyan,fontSize=11.sp,letterSpacing=2.sp)
            Text(if(step==1) "Learn the signal" else "Spot the break",color=white,fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp))
            Text(rule,color=muted,fontSize=14.sp,modifier=Modifier.padding(top=12.dp))
            Spacer(Modifier.height(70.dp)); ObjectGlyph(GameObject(if(targetAnomaly) Shape.TRIANGLE else Shape.CIRCLE, Hue.BLUE, 1), Modifier.size(150.dp), true)
            Spacer(Modifier.height(40.dp)); Text("Tap ${if(targetAnomaly)"ANOMALY" else "NORMAL"} to continue",color=white,fontSize=17.sp)
        }
        Row(Modifier.fillMaxWidth().height(100.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            DecisionZone("NORMAL","FOLLOWS THE RULE",cyan,Modifier.weight(1f)){if(!targetAnomaly)next()}
            DecisionZone("ANOMALY","BREAKS THE RULE",coral,Modifier.weight(1f)){if(targetAnomaly)next()}
        }
    }
}

@Composable private fun ResultsScreen(model:ArcadeModel,s:GameState,best:Int){
    val r=remember(s.stats,best){GameSessionEngine().let { GameResult(s.stats.score, maxOf(best,s.stats.score),if(s.stats.total==0)0 else s.stats.correct*100/s.stats.total,s.stats.correct,s.stats.bestCombo,s.stats.transitions,s.stats.score>best) }}
    Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween){
        Spacer(Modifier.height(8.dp)); Column(horizontalAlignment=Alignment.CenterHorizontally){Text(if(r.newBest)"NEW PERSONAL BEST" else "SESSION COMPLETE",color=if(r.newBest)amber else cyan,fontSize=11.sp,letterSpacing=2.sp);Text("${r.score}",color=white,fontSize=58.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=10.dp));Text("POINTS",color=muted,fontSize=10.sp,letterSpacing=2.sp)}
        Column(Modifier.fillMaxWidth().background(panel,RoundedCornerShape(20.dp)).padding(20.dp)){
            ResultLine("ACCURACY","${r.accuracy}%");ResultLine("CORRECT CLASSIFICATIONS","${r.correct}");ResultLine("BEST COMBO","${s.stats.bestCombo}×");ResultLine("TRANSITIONS SURVIVED","${s.stats.transitions}");ResultLine("PERSONAL BEST","${r.best}")
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally){BigButton("PLAY AGAIN",cyan,model::playAgain);Text("HOME",color=muted,fontSize=12.sp,letterSpacing=2.sp,modifier=Modifier.clickable(onClick=model::home).padding(20.dp))}
    }
}

@Composable private fun ResultLine(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=muted,fontSize=10.sp,letterSpacing=1.sp);Text(value,color=white,fontWeight=FontWeight.Bold,fontSize=13.sp)}}
@Composable private fun SettingsScreen(
    preferences: PlayerPreferences,
    onEcho: (Boolean) -> Unit,
    onSubtitles: (Boolean) -> Unit,
    onMusic: (Boolean) -> Unit,
    onSoundEffects: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    replayBriefing: () -> Unit,
    back: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("‹  BACK", color = cyan, modifier = Modifier.clickable(onClick = back).padding(vertical = 12.dp))
        Text("SETTINGS", color = white, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 12.dp))
        SettingRow("ECHO dialogue", preferences.echoDialogue, onEcho)
        SettingRow("Subtitles", preferences.subtitles, onSubtitles)
        SettingRow("Music", preferences.audio.musicEnabled, onMusic)
        SettingRow("Sound effects", preferences.audio.soundEffectsEnabled, onSoundEffects)
        SettingRow("Haptic feedback", preferences.haptics, onHaptics)
        Text("Music controls are ready for original tracks; no soundtrack is bundled yet.", color = muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        BigButton("REPLAY BRIEFING", cyan, replayBriefing)
    }
}
@Composable private fun SettingRow(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(label,color=white);Switch(value,onChange)}}
@Composable private fun BigButton(title:String,color:Color,onClick:()->Unit){Box(Modifier.fillMaxWidth().height(62.dp).background(color,RoundedCornerShape(18.dp)).clickable(onClick=onClick),contentAlignment=Alignment.Center){Text(title,color=bg,fontSize=15.sp,fontWeight=FontWeight.Black,letterSpacing=2.sp)}}

