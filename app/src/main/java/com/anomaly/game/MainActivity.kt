package com.anomaly.game

import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import com.anomaly.game.narrative.AssistanceLevel
import com.anomaly.game.narrative.EchoAssistanceEvaluator
import com.anomaly.game.narrative.EchoInterferenceController
import com.anomaly.game.narrative.InterferencePlan
import com.anomaly.game.narrative.InterferenceTransmission
import com.anomaly.game.narrative.InterferenceStage
import com.anomaly.game.narrative.CommunicationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.random.Random

private val android.content.Context.dataStore by preferencesDataStore("anomaly_settings")
private val bestKey = intPreferencesKey("personal_best")
private val completedRunsKey = intPreferencesKey("completed_runs")
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
        val barColor = android.graphics.Color.rgb(23, 28, 27)
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
    private val _transmission = MutableStateFlow<InterferenceTransmission?>(null)
    val transmission: StateFlow<InterferenceTransmission?> = _transmission.asStateFlow()
    private val _hintedAnomaly = MutableStateFlow<Boolean?>(null)
    val hintedAnomaly: StateFlow<Boolean?> = _hintedAnomaly.asStateFlow()
    private var assistance = EchoAssistanceEvaluator()
    private var interference = EchoInterferenceController(InterferencePlan.forCompletedRuns(0))
    private var runGeneration = 0
    private var runFinishedPersisted = true
    private var briefingReturnsToSettings = false
    private var runStartedAt = 0L
    private var consecutiveMistakes = 0
    private var pendingTransitionAtTotal: Int? = null
    private var finalSecondsNotified = false
    private var unusualSignalNotified = false

    fun requestPlay() {
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            if (prefs[briefingSeenKey] ?: false) startRun(resolveCompletedRuns(prefs), (prefs[echoDialogueKey] ?: true) && (prefs[subtitlesKey] ?: true)) else openBriefing(fromSettings = false)
        }
    }

    fun playAgain() { viewModelScope.launch {
        val prefs = context.dataStore.data.first()
        startRun(resolveCompletedRuns(prefs), (prefs[echoDialogueKey] ?: true) && (prefs[subtitlesKey] ?: true))
    } }

    private fun startRun(completedRuns: Int, communicationEnabled: Boolean) {
        _showBriefing.value = false
        _state.value = engine.state.copy(settings = false, tutorialStep = 0)
        echoManager.beginRun()
        _echoMessage.value = null
        _transmission.value = null
        _hintedAnomaly.value = null
        assistance.reset()
        val plan = if (communicationEnabled) InterferencePlan.forCompletedRuns(completedRuns) else InterferencePlan.forCompletedRuns(0)
        interference = EchoInterferenceController(plan)
        runGeneration++
        runFinishedPersisted = false
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
            if (engine.state.finished) recordCompletedRun()
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
        val reactionMillis = if (correct && round != null) (current.stats.reactionTotal - previous.stats.reactionTotal).coerceAtLeast(0L) else round?.deadlineMs ?: 0L
        val elapsed = (SystemClock.elapsedRealtime() - runStartedAt).coerceAtLeast(0L)
        assistance.record(correct, reactionMillis)
        _hintedAnomaly.value = null
        interference.objectResolved()?.let(::showInterferenceTransmission)

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

        val lowIntegrityEvent = previous.integrity > 1 && current.integrity == 1
        if (lowIntegrityEvent) sendDialogue(DialogueEvent.LOW_INTEGRITY)
        if (current.stats.transitions > previous.stats.transitions) {
            pendingTransitionAtTotal = current.stats.total
            if (current.stats.transitions >= 3 && !unusualSignalNotified) {
                unusualSignalNotified = true
                sendDialogue(DialogueEvent.UNUSUAL_SYSTEM)
            }
        }
        if (current.current != null) {
            val transitionEvent = current.stats.transitions > previous.stats.transitions ||
                current.message == "PATTERN SHIFT" || current.message == "SYSTEM ALERT · PATTERN SHIFT NEXT"
            val deferInterference = transitionEvent || lowIntegrityEvent || echoManager.hasActiveImportant(elapsed)
            interference.objectAvailable(current.stats.total + 1, deferInterference)?.let(::showInterferenceTransmission)

            if (!interference.suppressAssistance && current.running) {
                val level = assistance.eligibleHint(elapsed)
                if (level != AssistanceLevel.NONE) {
                    val event = if (level == AssistanceLevel.DIRECT) DialogueEvent.ASSISTANCE_HINT else DialogueEvent.ASSISTANCE_NUDGE
                    val target = if (current.current.isAnomaly) "ANOMALY" else "NORMAL"
                    if (sendDialogue(event, mapOf("{target}" to target))) {
                        assistance.markHintOffered(level, elapsed)
                        if (level == AssistanceLevel.DIRECT) _hintedAnomaly.value = current.current.isAnomaly
                    }
                }
            }
        }
        if (current.finished) recordCompletedRun()
    }

    private fun sendDialogue(event: DialogueEvent, replacements: Map<String, String> = emptyMap()): Boolean {
        val prefs = preferences.value
        if (!prefs.echoDialogue || !prefs.subtitles || interference.suppressOrdinaryDialogue) return false
        val elapsed = (SystemClock.elapsedRealtime() - runStartedAt).coerceAtLeast(0L)
        val offered = echoManager.offer(event, elapsed, EchoDialogueRepository.initialOperation.id) ?: return false
        val message = offered.copy(text = replacements.entries.fold(offered.text) { text, (key, value) -> text.replace(key, value) })
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

    private fun showInterferenceTransmission(message: InterferenceTransmission) {
        echoManager.interruptActive()
        _echoMessage.value = null
        _hintedAnomaly.value = null
        _transmission.value = message
        if (message.stage == InterferenceStage.RECOVERY_FIRST) {
            val generation = runGeneration
            viewModelScope.launch {
                delay(1_100L)
                if (generation != runGeneration || interference.status != CommunicationStatus.RECOVERING) return@launch
                interference.recoverySecond()?.let { _transmission.value = it }
                delay(1_500L)
                if (generation != runGeneration || interference.status != CommunicationStatus.RECOVERING) return@launch
                interference.finishRecovery()
                _transmission.value = null
            }
        }
    }

    private fun recordCompletedRun() {
        if (runFinishedPersisted) return
        runFinishedPersisted = true
        viewModelScope.launch {
            val newBest = engine.result(best.value).best
            context.dataStore.edit {
                it[bestKey] = newBest
                it[completedRunsKey] = (it[completedRunsKey] ?: 0) + 1
            }
        }
    }

    private suspend fun resolveCompletedRuns(prefs: androidx.datastore.preferences.core.Preferences): Int {
        val stored = prefs[completedRunsKey]
        if (stored != null) return stored
        // Existing installs have a best score but no run counter; treat them as past the clean onboarding runs.
        val migrated = if ((prefs[bestKey] ?: 0) > 0) 2 else 0
        if (migrated > 0) context.dataStore.edit { it[completedRunsKey] = migrated }
        return migrated
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
            if (returnToSettings) _state.value = _state.value.copy(settings = true)
            else {
                val prefs = context.dataStore.data.first()
                startRun(resolveCompletedRuns(prefs), (prefs[echoDialogueKey] ?: true) && (prefs[subtitlesKey] ?: true))
            }
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
private val bg = Color(0xFF171C1B)
private val bgSecondary = Color(0xFF202724)
private val panel = Color(0xFF29332E)
private val panelRaised = Color(0xFF313C36)
private val border = Color(0xFF48534A)
private val muted = Color(0xFF90998E)
private val cyan = Color(0xFFA9B99A)
private val coral = Color(0xFFB9655D)
private val amber = Color(0xFFD0A15C)
private val white = Color(0xFFC6C6B6)
private val objectBlue = Color(0xFF79A8A3)
private val objectGreen = Color(0xFFA9B99A)
private val systemMono = FontFamily.Monospace

private fun instructionWithColorWords(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val names = listOf(
        "blue" to objectBlue,
        "green" to objectGreen,
        "yellow" to amber,
        "coral" to coral,
        "red" to coral
    )
    names.forEach { (name, tint) ->
        Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            addStyle(SpanStyle(color = tint, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }
    }
}

@Composable private fun AnomalyApp() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val hostView = androidx.compose.ui.platform.LocalView.current
    val model: ArcadeModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ArcadeModel(context) as T })
    val state by model.state.collectAsState()
    val best by model.best.collectAsState()
    val preferences by model.preferences.collectAsState()
    val showBriefing by model.showBriefing.collectAsState()
    val echoMessage by model.echoMessage.collectAsState()
    val transmission by model.transmission.collectAsState()
    val hintedAnomaly by model.hintedAnomaly.collectAsState()
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
                    if (preferences.echoDialogue && preferences.subtitles) transmission else null,
                    hintedAnomaly,
                    onAnswer = { anomaly ->
                        if (preferences.haptics) {
                            val correct = state.current?.isAnomaly == anomaly
                            val semantic = if (correct) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.REJECT
                            val fallback = if (correct) android.view.HapticFeedbackConstants.KEYBOARD_TAP else android.view.HapticFeedbackConstants.LONG_PRESS
                            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) semantic else fallback
                            if (!hostView.performHapticFeedback(effect)) hostView.performHapticFeedback(fallback)
                        }
                        model.answer(anomaly)
                    }
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

@Composable private fun GameScreen(s: GameState, echoMessage: DialogueMessage?, transmission: InterferenceTransmission?, hintedAnomaly: Boolean?, onAnswer: (Boolean)->Unit) {
    val round=s.current; val rule=round?.ruleAtSpawn ?: s.rule
    val feedbackShake = remember { Animatable(0f) }
    LaunchedEffect(s.stats.total) {
        if (s.stats.total > 0 && s.lastCorrect == false) {
            // A tiny, brief nudge signals a mistake without flashing or moving the object.
            feedbackShake.snapTo(0f)
            feedbackShake.animateTo(1.5f, tween(45))
            feedbackShake.animateTo(-1.5f, tween(45))
            feedbackShake.animateTo(0f, tween(45))
        }
    }
    val transitionApproaching = s.message == "SYSTEM ALERT · PATTERN SHIFT NEXT"
    var protocolUpdateVisible by remember { mutableStateOf(false) }
    LaunchedEffect(s.stats.transitions) {
        if (s.stats.transitions > 0) {
            protocolUpdateVisible = true
            delay(1_700L)
            protocolUpdateVisible = false
        }
    }
    val transitionEmphasis = transitionApproaching || protocolUpdateVisible
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp, vertical=12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween) {
            Column { Text("SCORE", color=muted, fontFamily=systemMono, fontSize=10.sp, letterSpacing=1.5.sp); val score by androidx.compose.animation.core.animateIntAsState(s.stats.score, tween(320), label="score"); Text("$score", color=white, fontFamily=systemMono, fontSize=24.sp, fontWeight=FontWeight.Bold) }
            Column(horizontalAlignment=Alignment.CenterHorizontally) { Text("${(s.remainingMs/1000f).let { String.format("%.1f",it) }}", color=if(s.remainingMs<10000)coral else white, fontFamily=systemMono, fontSize=26.sp, fontWeight=FontWeight.Bold); Text("SEC", color=muted, fontFamily=systemMono, fontSize=9.sp) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("INT", color=muted, fontFamily=systemMono, fontSize=9.sp, modifier=Modifier.padding(end=4.dp)); repeat(3) { i -> val alive=i<s.integrity; val tint by androidx.compose.animation.animateColorAsState(if(alive) (if(s.integrity==1) amber else cyan) else border, tween(260), label="integrity-$i"); Text(if(alive) "◆" else "◇", color=tint, fontSize=17.sp, modifier=Modifier.padding(2.dp)) } }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().background(panelRaised, RoundedCornerShape(14.dp)).border(1.dp, if(transitionEmphasis) amber.copy(alpha=.5f) else border.copy(alpha=.55f), RoundedCornerShape(14.dp)).padding(horizontal=14.dp, vertical=12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Text("ACTIVE RULE", color=muted, fontFamily=systemMono, fontSize=9.sp, letterSpacing=1.7.sp)
                when {
                    transitionApproaching -> Text("TRANSITION NEXT", color=amber, fontFamily=systemMono, fontSize=8.sp, fontWeight=FontWeight.Bold, letterSpacing=.7.sp, modifier=Modifier.background(amber.copy(alpha=.12f), RoundedCornerShape(8.dp)).padding(horizontal=7.dp, vertical=4.dp))
                    protocolUpdateVisible -> Text("NEW PROTOCOL ACTIVE", color=amber, fontFamily=systemMono, fontSize=8.sp, fontWeight=FontWeight.Bold, letterSpacing=.7.sp, modifier=Modifier.background(amber.copy(alpha=.12f), RoundedCornerShape(8.dp)).padding(horizontal=7.dp, vertical=4.dp))
                }
            }
            Text(rule?.playerInstruction(s.history)?.let(::instructionWithColorWords) ?: AnnotatedString("Calibrating…"), color=cyan, fontSize=14.sp, lineHeight=18.sp, fontWeight=FontWeight.SemiBold, maxLines=3, modifier=Modifier.padding(top=4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top=12.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
            Text("RECENT", color=muted, fontFamily=systemMono, fontSize=9.sp, letterSpacing=1.5.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) { s.history.takeLast(5).forEach { ObjectGlyph(it, Modifier.size(26.dp)) } }
        }
        Spacer(Modifier.height(8.dp))
        EchoCommunicationPanel(echoMessage?.takeUnless { it.event == DialogueEvent.PATTERN_TRANSITION }, transmission, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().offset(x=feedbackShake.value.dp).background(Brush.verticalGradient(listOf(bgSecondary, bg)), RoundedCornerShape(20.dp)).border(1.dp, if(s.integrity==1) amber.copy(alpha=.6f) else border.copy(alpha=.55f), RoundedCornerShape(20.dp)), contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                var y=6.dp.toPx(); while(y<size.height) { drawLine(white.copy(alpha=.018f), Offset(0f,y), Offset(size.width,y), 1.dp.toPx()); y+=6.dp.toPx() }
            }
            if(round!=null) ObjectGlyph(round.item, Modifier.size(190.dp).offset(y=12.dp))
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal=12.dp, vertical=12.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                if(s.message.isNotBlank() && s.message != "SYSTEM ALERT · PATTERN SHIFT NEXT" && s.message != "PATTERN SHIFT") Text(s.message, color=if(s.lastCorrect==false)coral else amber, fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=2.sp)
                Text(rule?.focusCue(s.history)?.let(::instructionWithColorWords) ?: AnnotatedString("READ THE ACTIVE RULE"), color=cyan, fontSize=14.sp, fontWeight=FontWeight.Bold, textAlign=TextAlign.Center, maxLines=2, modifier=Modifier.padding(top=6.dp))
                if(s.stats.combo>=3) Text("${s.stats.combo}× COMBO${if(s.stats.combo>=5) "  ·  MULTIPLIER" else if(s.stats.combo==3) "  ·  STREAK" else ""}", color=cyan, fontFamily=systemMono, fontSize=10.sp, letterSpacing=1.2.sp, modifier=Modifier.padding(top=4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            DecisionZone("NORMAL", "FOLLOWS THE RULE", cyan, Modifier.weight(1f), hinted=hintedAnomaly == false, onClick={onAnswer(false)})
            DecisionZone("ANOMALY", "BREAKS THE RULE", coral, Modifier.weight(1f), hinted=hintedAnomaly == true, onClick={onAnswer(true)})
        }
    }
}

@Composable private fun EchoCommunicationPanel(message: DialogueMessage?, transmission: InterferenceTransmission?, modifier: Modifier = Modifier) {
    val takeover = transmission?.status == CommunicationStatus.COMPROMISED
    val accent = when (transmission?.status) {
        CommunicationStatus.WARNING -> amber
        CommunicationStatus.COMPROMISED -> coral
        CommunicationStatus.RECOVERING -> cyan
        else -> if (message?.event in setOf(DialogueEvent.LOW_INTEGRITY, DialogueEvent.FINAL_SECONDS)) amber else cyan
    }
    val status = when (transmission?.stage) {
        InterferenceStage.WARNING, InterferenceStage.WARNING_SECOND -> "SIGNAL WARNING"
        InterferenceStage.TAKEOVER -> "COMPROMISED"
        InterferenceStage.RECOVERY_FIRST -> "RECONNECTING"
        InterferenceStage.RECOVERY_SECOND -> "RESTORED"
        null -> when (message?.event) {
            DialogueEvent.LOW_INTEGRITY -> "CRITICAL"
            DialogueEvent.FINAL_SECONDS -> "WARNING"
            null -> if(message == null) "ONLINE" else "TRANSMITTING"
            else -> "TRANSMITTING"
        }
    }
    val shortMessage = when (transmission?.stage) {
        InterferenceStage.WARNING -> "EXTERNAL ACCESS DETECTED"
        InterferenceStage.WARNING_SECOND -> "INTERFERENCE DETECTED"
        InterferenceStage.TAKEOVER -> "DON'T LISTEN TO ECHO."
        InterferenceStage.RECOVERY_FIRST -> "OPERATOR? CAN YOU HEAR ME?"
        InterferenceStage.RECOVERY_SECOND -> "THAT WASN'T ME."
        null -> when (message?.event) {
            null -> "MONITORING SIGNAL"
            DialogueEvent.ASSISTANCE_NUDGE -> "CHECK THE ACTIVE RULE"
            DialogueEvent.ASSISTANCE_HINT -> Regex("NORMAL|ANOMALY").find(message.text)?.value?.let { "THIS OBJECT: $it" } ?: "CHECK THE CURRENT OBJECT"
            DialogueEvent.PATTERN_TRANSITION -> "NEW PROTOCOL"
            DialogueEvent.TRANSITION_SURVIVED -> "PROTOCOL HELD"
            DialogueEvent.REPEATED_MISTAKES -> "RECHECK THE ACTIVE RULE"
            DialogueEvent.LOW_INTEGRITY -> "INTEGRITY CRITICAL"
            DialogueEvent.FINAL_SECONDS -> "TEN SECONDS REMAIN"
            DialogueEvent.UNUSUAL_SYSTEM -> "UNUSUAL SIGNAL DETECTED"
            DialogueEvent.FIRST_STREAK -> "STREAK DETECTED"
            DialogueEvent.COMBO_MILESTONE -> "COMBO MILESTONE"
            DialogueEvent.SUBTLE_CATCH -> "ANOMALY CONFIRMED"
            DialogueEvent.HIGH_PERFORMANCE -> "PERFORMANCE ABOVE BASELINE"
        }
    }
    val waveform = remember { Animatable(.22f) }
    val isTransmitting = transmission != null || message != null
    LaunchedEffect(message?.id, transmission?.stage) {
        if (isTransmitting) {
            repeat(2) {
                waveform.animateTo(.9f, tween(100))
                waveform.animateTo(.25f, tween(160))
            }
        } else waveform.snapTo(.22f)
    }

    Row(
        modifier.height(48.dp)
            .background(if(takeover) coral.copy(alpha=.09f) else bgSecondary, RoundedCornerShape(9.dp))
            .border(1.dp, accent.copy(alpha=if(takeover) .42f else .26f), RoundedCornerShape(9.dp))
            .padding(horizontal=10.dp, vertical=5.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(accent, RoundedCornerShape(50)))
        Column(Modifier.weight(1f).padding(start=8.dp), verticalArrangement=Arrangement.Center) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("ECHO", color=white, fontFamily=systemMono, fontSize=9.sp, fontWeight=FontWeight.Bold, letterSpacing=1.1.sp)
                Text("  /  $status", color=accent, fontFamily=systemMono, fontSize=8.sp, fontWeight=FontWeight.Bold, letterSpacing=.8.sp, maxLines=1, overflow=TextOverflow.Ellipsis)
            }
            Text(shortMessage, color=white, fontFamily=if(takeover) systemMono else FontFamily.Default, fontSize=10.sp, lineHeight=12.sp, maxLines=1, overflow=TextOverflow.Ellipsis)
        }
        Canvas(Modifier.width(34.dp).height(20.dp)) {
            val base = listOf(.28f, .62f, .4f, .82f, .36f)
            val gap = 5.dp.toPx()
            base.forEachIndexed { index, value ->
                val amp = value * if(isTransmitting) waveform.value else .38f
                val barHeight = size.height * amp
                val x = index * gap + 2.dp.toPx()
                if(takeover && index == 2) {
                    drawLine(accent.copy(alpha=.7f), Offset(x, size.height*.25f), Offset(x+2.dp.toPx(), size.height*.25f), strokeWidth=2.dp.toPx())
                    drawLine(accent.copy(alpha=.55f), Offset(x+4.dp.toPx(), size.height*.8f), Offset(x+6.dp.toPx(), size.height*.8f), strokeWidth=2.dp.toPx())
                } else {
                    drawLine(accent.copy(alpha=if(isTransmitting) .8f else .42f), Offset(x, (size.height-barHeight)/2), Offset(x, (size.height+barHeight)/2), strokeWidth=2.dp.toPx())
                }
            }
        }
    }
}

@Composable private fun DecisionZone(title:String, sub:String, color:Color, modifier:Modifier, hinted:Boolean=false, onClick:()->Unit) {
    Column(modifier.fillMaxHeight().background(color.copy(alpha=if(hinted) .15f else .09f), RoundedCornerShape(18.dp)).border(1.dp,color.copy(alpha=if(hinted) .72f else .42f),RoundedCornerShape(18.dp)).clickable(onClick=onClick).padding(12.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
        if(hinted) Text("ECHO SIGNAL",color=color,fontFamily=systemMono,fontSize=7.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp,modifier=Modifier.padding(bottom=4.dp))
        Text(title,color=color,fontSize=19.sp,fontWeight=FontWeight.Black,letterSpacing=1.5.sp); Text(sub,color=muted,fontSize=8.sp,letterSpacing=1.sp,modifier=Modifier.padding(top=5.dp))
    }
}

@Composable private fun ObjectGlyph(item: GameObject, modifier: Modifier, glow:Boolean=false) {
    val color=when(item.hue){Hue.CORAL->coral;Hue.BLUE->objectBlue;Hue.YELLOW->amber;Hue.GREEN->objectGreen}
    Canvas(modifier) {
        val unit=size.minDimension*.64f; val left=(size.width-unit)/2; val top=(size.height-unit)/2; val center=Offset(size.width/2,size.height/2)
        if(glow) drawCircle(color.copy(alpha=.06f),unit*.78f,center)
        when(item.shape){
            Shape.CIRCLE->{drawCircle(color,unit*.46f,center);drawCircle(white.copy(alpha=.16f),unit*.46f,center,style=Stroke(width=1.5.dp.toPx()))}
            Shape.SQUARE->{drawRoundRect(color,Offset(left,top),Size(unit,unit),androidx.compose.ui.geometry.CornerRadius(unit*.06f));drawRoundRect(white.copy(alpha=.16f),Offset(left,top),Size(unit,unit),androidx.compose.ui.geometry.CornerRadius(unit*.06f),style=Stroke(width=1.5.dp.toPx()))}
            Shape.TRIANGLE->{val p=Path().apply{moveTo(center.x,top);lineTo(left+unit,top+unit*.88f);lineTo(left,top+unit*.88f);close()};drawPath(p,color);drawPath(p,white.copy(alpha=.16f),style=Stroke(width=1.5.dp.toPx()))}
        }
        val count=item.dots.coerceIn(0,3)
        if(count>0){val step=unit*.35f/2.6f;val dot=minOf(size.minDimension*.044f,step*.31f);val positions=when(count){1->listOf(Offset(center.x,center.y));2->listOf(Offset(center.x-step,center.y),Offset(center.x+step,center.y));else->listOf(Offset(center.x,center.y-step*.72f),Offset(center.x-step,center.y+step*.68f),Offset(center.x+step,center.y+step*.68f))};positions.forEach{p->drawCircle(bg,dot*1.45f,p);drawCircle(bg.copy(alpha=.88f),dot,p)}}
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
    val failure = s.endReason == RunEndReason.INTEGRITY_DEPLETED
    val finalDecision = s.decisionHistory.lastOrNull().takeIf { failure }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)){
        Column(horizontalAlignment=Alignment.CenterHorizontally){Text(when { failure -> "SIGNAL LOST"; r.newBest -> "NEW PERSONAL BEST"; else -> "SESSION COMPLETE" },color=when { failure -> coral; r.newBest -> amber; else -> cyan },fontFamily=systemMono,fontSize=11.sp,letterSpacing=2.sp);Text("${r.score}",color=white,fontSize=58.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=10.dp));Text("POINTS",color=muted,fontFamily=systemMono,fontSize=10.sp,letterSpacing=2.sp)}
        Column(Modifier.fillMaxWidth().background(panel,RoundedCornerShape(20.dp)).padding(20.dp)){
            ResultLine("ACCURACY","${r.accuracy}%");ResultLine("CORRECT CLASSIFICATIONS","${r.correct}");ResultLine("BEST COMBO","${s.stats.bestCombo}×");ResultLine("TRANSITIONS SURVIVED","${s.stats.transitions}");ResultLine("PERSONAL BEST","${r.best}")
        }
        if(finalDecision != null) FinalDecisionAnalysis(finalDecision)
        Column(Modifier.fillMaxWidth().padding(top=4.dp),horizontalAlignment=Alignment.CenterHorizontally){BigButton("PLAY AGAIN",cyan,model::playAgain);Text("HOME",color=muted,fontSize=12.sp,letterSpacing=2.sp,modifier=Modifier.clickable(onClick=model::home).padding(20.dp))}
    }
}

@Composable private fun FinalDecisionAnalysis(decision: DecisionContext) {
    Column(Modifier.fillMaxWidth().background(bgSecondary,RoundedCornerShape(18.dp)).border(1.dp,coral.copy(alpha=.55f),RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text("FINAL DECISION ANALYSIS",color=coral,fontFamily=systemMono,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.3.sp)
        Text("ACTIVE PROTOCOL",color=muted,fontFamily=systemMono,fontSize=8.sp,letterSpacing=1.1.sp,modifier=Modifier.padding(top=14.dp))
        Text(decision.activeRuleDescription,color=cyan,fontSize=14.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=4.dp))

        Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
            ObjectGlyph(decision.item,Modifier.size(74.dp))
            Column(Modifier.padding(start=12.dp)) {
                Text("INCOMING OBJECT",color=muted,fontFamily=systemMono,fontSize=8.sp,letterSpacing=1.sp)
                val shape=decision.item.shape.name.lowercase().replaceFirstChar(Char::uppercase)
                val hue=decision.item.hue.name.lowercase().replaceFirstChar(Char::uppercase)
                Text("$shape · $hue · ${decision.item.dots} ${if(decision.item.dots==1) "DOT" else "DOTS"}",color=white,fontSize=11.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=4.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        ResultLine("YOUR LAST DECISION",when {
            decision.timedOut && decision.playerAnomaly == null -> "TIMEOUT · NO RESPONSE"
            decision.lateResponse -> "LATE · ${decision.playerAnomaly?.toDecisionLabel()}"
            else -> decision.playerAnomaly?.toDecisionLabel() ?: "NO RESPONSE"
        })
        ResultLine("CORRECT CLASSIFICATION",decision.correctAnomaly.toDecisionLabel())
        Text(decision.explanation,color=white,fontSize=12.sp,lineHeight=17.sp,modifier=Modifier.padding(top=4.dp))

        if(decision.recentTransition && decision.previousRule != null && decision.newRule != null) {
            Column(Modifier.fillMaxWidth().padding(top=14.dp).background(panel,RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("RECENT PROTOCOL CHANGE",color=amber,fontFamily=systemMono,fontSize=8.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)
                Text("PREVIOUS PROTOCOL",color=muted,fontFamily=systemMono,fontSize=8.sp,letterSpacing=.8.sp,modifier=Modifier.padding(top=8.dp))
                Text(decision.previousRuleDescription ?: decision.previousRule.label,color=white,fontSize=11.sp,modifier=Modifier.padding(top=3.dp))
                Text("NEW PROTOCOL",color=amber,fontFamily=systemMono,fontSize=8.sp,letterSpacing=.8.sp,modifier=Modifier.padding(top=8.dp))
                Text(decision.newRuleDescription ?: decision.newRule.label,color=cyan,fontSize=11.sp,modifier=Modifier.padding(top=3.dp))
            }
        }

        val echoObservation = when {
            decision.recentTransition -> "The protocol had just changed. Check the active rule above."
            decision.timedOut -> "The signal expired before you answered."
            decision.lateResponse -> "Your selection arrived after the response window closed."
            else -> null
        }
        if(echoObservation != null) Text("ECHO  /  $echoObservation",color=cyan,fontSize=10.sp,lineHeight=14.sp,modifier=Modifier.padding(top=12.dp))
    }
}

private fun Boolean.toDecisionLabel(): String = if(this) "ANOMALY" else "NORMAL"

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

