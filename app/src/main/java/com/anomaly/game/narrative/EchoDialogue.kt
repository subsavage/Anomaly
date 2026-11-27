package com.anomaly.game.narrative

enum class DialoguePriority(val rank: Int) { ROUTINE(0), ASSISTANCE(1), IMPORTANT(2), CRITICAL(3) }

enum class DialogueEvent(val priority: DialoguePriority) {
    FIRST_STREAK(DialoguePriority.ROUTINE),
    COMBO_MILESTONE(DialoguePriority.ROUTINE),
    SUBTLE_CATCH(DialoguePriority.ROUTINE),
    REPEATED_MISTAKES(DialoguePriority.IMPORTANT),
    PATTERN_TRANSITION(DialoguePriority.IMPORTANT),
    TRANSITION_SURVIVED(DialoguePriority.ROUTINE),
    HIGH_PERFORMANCE(DialoguePriority.ROUTINE),
    ASSISTANCE_NUDGE(DialoguePriority.ASSISTANCE),
    ASSISTANCE_HINT(DialoguePriority.ASSISTANCE),
    LOW_INTEGRITY(DialoguePriority.CRITICAL),
    FINAL_SECONDS(DialoguePriority.CRITICAL),
    UNUSUAL_SYSTEM(DialoguePriority.IMPORTANT)
}

data class DialogueMessage(
    val id: Long,
    val event: DialogueEvent,
    val text: String,
    val priority: DialoguePriority,
    val durationMillis: Long
)

data class OperationMetadata(
    val id: String,
    val title: String,
    val subtitle: String,
    val objective: String,
    val briefing: List<String>,
    val dialogue: Map<DialogueEvent, List<String>>
)

object EchoDialogueRepository {
    private val calibrationDialogue = mapOf(
        DialogueEvent.FIRST_STREAK to listOf(
            "You're finding the rhythm. That's the easy part.",
            "Good. You're reading the feed instead of guessing.",
            "A clean run of calls. Keep your eyes moving."
        ),
        DialogueEvent.COMBO_MILESTONE to listOf(
            "Not bad, Operator. I'll pretend not to be impressed.",
            "That rhythm suits you. The system may notice, too.",
            "Five clean calls. Try not to make it look effortless."
        ),
        DialogueEvent.SUBTLE_CATCH to listOf(
            "Good catch. That one was subtle.",
            "You saw the small mismatch. I nearly missed it on my end.",
            "There. That's the kind of detail they leave out of the report."
        ),
        DialogueEvent.REPEATED_MISTAKES to listOf(
            "Take a breath. Recheck the rule. I've got the channel.",
            "Two bad reads. Reset your eyes on the active signal.",
            "Easy. The feed is noisy; you don't have to be."
        ),
        DialogueEvent.PATTERN_TRANSITION to listOf(
            "New protocol. Set the old sequence aside.",
            "Rule changed. Read what's on screen; the old pattern is done.",
            "New signal. The system never did enjoy a quiet shift."
        ),
        DialogueEvent.TRANSITION_SURVIVED to listOf(
            "System stabilized. For now.",
            "You adapted quickly. I'll update the report. Carefully.",
            "New protocol held. That's one thing they got right."
        ),
        DialogueEvent.HIGH_PERFORMANCE to listOf(
            "I might have underestimated you.",
            "That was precise. I won't tell Control you made it look easy.",
            "Excellent work. I said that once; don't make me repeat it."
        ),
        DialogueEvent.ASSISTANCE_NUDGE to listOf(
            "Compare this one with the active rule.",
            "Check the current protocol before you call it.",
            "The answer is in the rule that's still on screen."
        ),
        DialogueEvent.ASSISTANCE_HINT to listOf(
            "Next one: {target}.",
            "I have this one as {target}.",
            "For this object, choose {target}."
        ),
        DialogueEvent.LOW_INTEGRITY to listOf(
            "You're down to your last buffer. Watch the active rule.",
            "One integrity point left. I can buy you focus, not time."
        ),
        DialogueEvent.FINAL_SECONDS to listOf(
            "One last push. We can discuss the timing afterward.",
            "Ten seconds. Stay with the signal; I'll handle the channel."
        ),
        DialogueEvent.UNUSUAL_SYSTEM to listOf(
            "That trace predates this run. It shouldn't be in your feed.",
            "They called these random. That one repeated a sequence.",
            "This wasn't in my briefing. I'm checking what they sent me."
        )
    )

    val operations: List<OperationMetadata> = listOf(
        OperationMetadata(
            id = "001",
            title = "CALIBRATION",
            subtitle = "OPERATION 001 · LIVE FEED",
            objective = "Classify each incoming object against the active protocol.",
            briefing = listOf(
                "Operator, we're seeing an unusual pattern in the feed.",
                "Your job is simple: flag anything that doesn't belong.",
                "Left is NORMAL. Right is ANOMALY. The active rule stays on screen.",
                "Don't overthink it. And try not to let the system get inside your head."
            ),
            dialogue = calibrationDialogue
        ),
        OperationMetadata(
            id = "002",
            title = "IRREGULARITY",
            subtitle = "OPERATION 002 · RESTRICTED FEED",
            objective = "Follow the new protocol. Compare the feed with prior reports.",
            briefing = listOf(
                "The same irregularity has appeared in three separate feeds.",
                "Control calls that coincidence. I am less convinced.",
                "Classify what you see. I'll compare the signal against the archive."
            ),
            dialogue = calibrationDialogue + (DialogueEvent.UNUSUAL_SYSTEM to listOf("That signature matches the other feeds. Control said there were none."))
        ),
        OperationMetadata(
            id = "003",
            title = "CONTAINMENT",
            subtitle = "OPERATION 003 · SYSTEM INSTABILITY",
            objective = "Adapt as protocols shift and preserve system integrity.",
            briefing = listOf(
                "The system is changing protocols faster than the report predicts.",
                "Trust the live rule, not the briefing. I'll stay on the channel."
            ),
            dialogue = calibrationDialogue + (DialogueEvent.UNUSUAL_SYSTEM to listOf("I was told this transition was scheduled. The timestamp says otherwise."))
        ),
        OperationMetadata(
            id = "004",
            title = "UNKNOWN SIGNAL",
            subtitle = "OPERATION 004 · SOURCE UNVERIFIED",
            objective = "Identify deviations without assuming the source is understood.",
            briefing = listOf(
                "There's a signal in the feed that doesn't match our system map.",
                "I can't confirm who added it. For now, classify the data in front of you."
            ),
            dialogue = calibrationDialogue + (DialogueEvent.UNUSUAL_SYSTEM to listOf("That response came from inside the system. No one told me it could answer."))
        )
    )

    val initialOperation: OperationMetadata get() = operations.first()
    fun operation(id: String): OperationMetadata? = operations.firstOrNull { it.id == id }
}

/** Stateful, UI-independent selector. It never owns or changes gameplay timing. */
class EchoDialogueManager(
    private val repository: EchoDialogueRepository = EchoDialogueRepository,
    private val routineCooldownMillis: Long = 10_000L,
    private val routineDurationMillis: Long = 2_800L,
    private val importantDurationMillis: Long = 3_600L
) {
    private val nextLineIndex = mutableMapOf<Pair<String, DialogueEvent>, Int>()
    private val lastLine = mutableMapOf<Pair<String, DialogueEvent>, String>()
    private var nextMessageId = 1L
    private var lastRoutineAt: Long? = null
    private var activeUntil = 0L
    var activeMessage: DialogueMessage? = null
        private set

    fun beginRun() {
        activeMessage = null
        activeUntil = 0L
        lastRoutineAt = null
    }

    fun offer(event: DialogueEvent, elapsedMillis: Long, operationId: String = "001"): DialogueMessage? {
        if (activeMessage != null && elapsedMillis >= activeUntil) activeMessage = null
        val current = activeMessage
        if (current != null && current.priority.rank >= DialoguePriority.IMPORTANT.rank && event.priority.rank <= current.priority.rank) return null
        if (current != null && event.priority.rank <= current.priority.rank) return null
        if (event.priority == DialoguePriority.ROUTINE && lastRoutineAt?.let { elapsedMillis - it < routineCooldownMillis } == true) return null

        val operation = repository.operation(operationId) ?: return null
        val lines = operation.dialogue[event].orEmpty()
        if (lines.isEmpty()) return null
        val key = operationId to event
        val previous = lastLine[key]
        val candidates = if (lines.size > 1) lines.filterNot { it == previous } else lines
        val index = nextLineIndex[key] ?: 0
        val text = candidates[index % candidates.size]
        nextLineIndex[key] = index + 1
        lastLine[key] = text
        val duration = if (event.priority.rank >= DialoguePriority.IMPORTANT.rank) importantDurationMillis else routineDurationMillis
        val message = DialogueMessage(nextMessageId++, event, text, event.priority, duration)
        activeMessage = message
        activeUntil = elapsedMillis + duration
        if (event.priority == DialoguePriority.ROUTINE) lastRoutineAt = elapsedMillis
        return message
    }

    fun dismiss(messageId: Long) {
        if (activeMessage?.id == messageId) activeMessage = null
    }

    fun hasActiveImportant(elapsedMillis: Long): Boolean {
        if (activeMessage != null && elapsedMillis >= activeUntil) activeMessage = null
        return activeMessage?.priority?.rank?.let { it >= DialoguePriority.IMPORTANT.rank } == true
    }

    /** Lets a higher-priority system transmission take the channel without resetting cooldown history. */
    fun interruptActive() {
        activeMessage = null
        activeUntil = 0L
    }
}
