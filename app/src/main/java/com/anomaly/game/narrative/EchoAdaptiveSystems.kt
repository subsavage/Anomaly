package com.anomaly.game.narrative

import java.util.ArrayDeque

enum class AssistanceLevel { NONE, SUBTLE, DIRECT }

/** Evaluates resolved decisions only; it has no access to objects or classification. */
class EchoAssistanceEvaluator(
    private val windowSize: Int = 8,
    private val accuracyThreshold: Double = .65,
    private val recoveryThreshold: Double = .75,
    private val consecutiveMistakeThreshold: Int = 3,
    private val cooldownMillis: Long = 12_000L,
    private val decisionsBetweenHints: Int = 4,
    private val slowReactionThresholdMillis: Long = 2_000L
) {
    private val recent = ArrayDeque<Pair<Boolean, Long>>()
    private var mistakesInRow = 0
    private var decisions = 0
    private var lastHintAt: Long? = null
    private var lastHintDecision: Int? = null
    private var lastHintLevel = AssistanceLevel.NONE

    val recentAccuracy: Double? get() = if (recent.isEmpty()) null else recent.count { it.first }.toDouble() / recent.size
    val recentAverageReactionMillis: Long? get() = if(recent.isEmpty()) null else recent.sumOf { it.second } / recent.size
    val consecutiveMistakes: Int get() = mistakesInRow

    fun record(correct: Boolean, reactionMillis: Long = 0L) {
        decisions++
        recent.addLast(correct to reactionMillis.coerceAtLeast(0L))
        while (recent.size > windowSize) recent.removeFirst()
        mistakesInRow = if (correct) 0 else mistakesInRow + 1
        if (recent.size == windowSize && recentAccuracy!! >= recoveryThreshold && mistakesInRow == 0) {
            lastHintLevel = AssistanceLevel.NONE
        }
    }

    fun reset() {
        recent.clear()
        mistakesInRow = 0
        decisions = 0
        lastHintAt = null
        lastHintDecision = Int.MIN_VALUE
        lastHintLevel = AssistanceLevel.NONE
    }

    fun eligibleHint(nowMillis: Long): AssistanceLevel {
        val accuracy = recentAccuracy
        val evaluatedWindow = recent.size == windowSize
        val lowAccuracy = evaluatedWindow && accuracy != null && accuracy < accuracyThreshold
        val slowAndStrained = evaluatedWindow && accuracy != null && accuracy < .8 &&
            (recentAverageReactionMillis ?: 0L) >= slowReactionThresholdMillis
        val repeatedErrors = mistakesInRow >= consecutiveMistakeThreshold
        if (!lowAccuracy && !slowAndStrained && !repeatedErrors) return AssistanceLevel.NONE
        if (lastHintDecision?.let { decisions - it < decisionsBetweenHints } == true) return AssistanceLevel.NONE
        if (lastHintAt?.let { nowMillis - it < cooldownMillis } == true) return AssistanceLevel.NONE
        val level = if (repeatedErrors) AssistanceLevel.DIRECT else AssistanceLevel.SUBTLE
        if (level == lastHintLevel && lastHintDecision?.let { decisions - it < windowSize } == true) return AssistanceLevel.NONE
        return level
    }

    fun markHintOffered(level: AssistanceLevel, nowMillis: Long) {
        if (level == AssistanceLevel.NONE) return
        lastHintAt = nowMillis
        lastHintDecision = decisions
        lastHintLevel = level
    }
}

enum class CommunicationStatus { NORMAL, WARNING, COMPROMISED, RECOVERING }
enum class InterferenceStage { WARNING, WARNING_SECOND, TAKEOVER, RECOVERY_FIRST, RECOVERY_SECOND }

data class InterferenceTransmission(val status: CommunicationStatus, val stage: InterferenceStage, val text: String)

data class InterferencePlan(
    val firstWarningObject: Int?,
    val maxEvents: Int,
    val intervalObjects: Int,
    val compromisedObjects: Int = 3
) {
    val enabled: Boolean get() = firstWarningObject != null && maxEvents > 0

    companion object {
        /** completedRuns is local, persisted progression. Runs 1–2 remain interruption-free. */
        fun forCompletedRuns(completedRuns: Int): InterferencePlan = when {
            completedRuns < 2 -> InterferencePlan(null, 0, Int.MAX_VALUE)
            completedRuns < 6 -> InterferencePlan(firstWarningObject = 10, maxEvents = 1, intervalObjects = Int.MAX_VALUE)
            completedRuns < 10 -> InterferencePlan(firstWarningObject = 7, maxEvents = 2, intervalObjects = 11)
            else -> InterferencePlan(firstWarningObject = 6, maxEvents = 2, intervalObjects = 8)
        }
    }
}

/** Deterministic communications-only state machine, advanced by object arrival/resolution. */
class EchoInterferenceController(private val plan: InterferencePlan) {
    var status: CommunicationStatus = CommunicationStatus.NORMAL
        private set
    var eventsStarted: Int = 0
        private set
    private var warningObject = 0
    private var takeoverObject = 0
    private var compromisedRemaining = 0
    private var nextWarningObject = plan.firstWarningObject ?: Int.MAX_VALUE
    private var secondWarningSent = false

    fun objectAvailable(objectIndex: Int, deferForCriticalEvent: Boolean = false): InterferenceTransmission? {
        if (!plan.enabled || deferForCriticalEvent) return null
        if (status == CommunicationStatus.NORMAL && eventsStarted < plan.maxEvents && objectIndex >= nextWarningObject) {
            status = CommunicationStatus.WARNING
            warningObject = objectIndex
            takeoverObject = objectIndex + 2
            secondWarningSent = false
            return InterferenceTransmission(status, InterferenceStage.WARNING, "Operator, something's accessing my feed.")
        }
        if (status == CommunicationStatus.WARNING && objectIndex >= warningObject + 1 && !secondWarningSent) {
            secondWarningSent = true
            return InterferenceTransmission(status, InterferenceStage.WARNING_SECOND, "Wait. I'm seeing interference.")
        }
        if (status == CommunicationStatus.WARNING && objectIndex >= takeoverObject) {
            status = CommunicationStatus.COMPROMISED
            compromisedRemaining = plan.compromisedObjects
            eventsStarted++
            return InterferenceTransmission(status, InterferenceStage.TAKEOVER, "DON'T LISTEN TO ECHO.")
        }
        return null
    }

    fun objectResolved(): InterferenceTransmission? {
        if (status != CommunicationStatus.COMPROMISED) return null
        compromisedRemaining--
        if (compromisedRemaining > 0) return null
        status = CommunicationStatus.RECOVERING
        return InterferenceTransmission(status, InterferenceStage.RECOVERY_FIRST, "Operator? Can you hear me?")
    }

    fun recoverySecond(): InterferenceTransmission? = if (status == CommunicationStatus.RECOVERING) {
        InterferenceTransmission(status, InterferenceStage.RECOVERY_SECOND, "Okay. I'm back. That wasn't me.")
    } else null

    fun finishRecovery() {
        if (status != CommunicationStatus.RECOVERING) return
        status = CommunicationStatus.NORMAL
        if (eventsStarted < plan.maxEvents) nextWarningObject = warningObject + plan.intervalObjects
    }

    val suppressOrdinaryDialogue: Boolean get() = status != CommunicationStatus.NORMAL
    val suppressAssistance: Boolean get() = status != CommunicationStatus.NORMAL
}
