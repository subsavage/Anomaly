package com.anomaly.game.narrative

import org.junit.Assert.*
import org.junit.Test

class EchoDialogueManagerTest {
    @Test fun routineMessagesRespectTenSecondCooldown() {
        val manager = EchoDialogueManager()
        val first = manager.offer(DialogueEvent.FIRST_STREAK, 0)!!
        manager.dismiss(first.id)
        assertNull(manager.offer(DialogueEvent.COMBO_MILESTONE, 9_999))
        assertNotNull(manager.offer(DialogueEvent.COMBO_MILESTONE, 10_000))
    }

    @Test fun importantMessageCanOverrideRoutineCooldownAndVisibleRoutine() {
        val manager = EchoDialogueManager()
        val routine = manager.offer(DialogueEvent.FIRST_STREAK, 1_000)!!
        val important = manager.offer(DialogueEvent.LOW_INTEGRITY, 1_100)
        assertNotNull(important)
        assertNotEquals(routine.id, important!!.id)
        assertEquals(DialoguePriority.CRITICAL, manager.activeMessage?.priority)
    }

    @Test fun activeImportantMessageCannotBeInterrupted() {
        val manager = EchoDialogueManager()
        val first = manager.offer(DialogueEvent.PATTERN_TRANSITION, 500)!!
        assertNull(manager.offer(DialogueEvent.PATTERN_TRANSITION, 600))
        assertNull(manager.offer(DialogueEvent.FIRST_STREAK, 700))
        assertEquals(first, manager.activeMessage)
    }

    @Test fun adaptiveAssistanceCanReplaceRoutineButCriticalWarningsCanReplaceTransitions() {
        val manager = EchoDialogueManager()
        manager.offer(DialogueEvent.FIRST_STREAK, 0)
        val assistance = manager.offer(DialogueEvent.ASSISTANCE_HINT, 100)
        assertEquals(DialoguePriority.ASSISTANCE, assistance?.priority)
        val transition = manager.offer(DialogueEvent.PATTERN_TRANSITION, 200)
        assertEquals(DialoguePriority.IMPORTANT, transition?.priority)
        val critical = manager.offer(DialogueEvent.FINAL_SECONDS, 300)
        assertEquals(DialoguePriority.CRITICAL, critical?.priority)
    }

    @Test fun alternativeLinesAvoidImmediateRepetition() {
        val manager = EchoDialogueManager()
        val first = manager.offer(DialogueEvent.FIRST_STREAK, 0)!!
        val second = manager.offer(DialogueEvent.FIRST_STREAK, 10_000)!!
        val third = manager.offer(DialogueEvent.FIRST_STREAK, 20_000)!!
        assertNotEquals(first.text, second.text)
        assertNotEquals(second.text, third.text)
    }

    @Test fun operationSpecificDialogueAndBriefingAreAvailable() {
        val operation = EchoDialogueRepository.operation("004")!!
        assertEquals("UNKNOWN SIGNAL", operation.title)
        assertTrue(operation.briefing.isNotEmpty())
        val message = EchoDialogueManager().offer(DialogueEvent.UNUSUAL_SYSTEM, 0, operationId = "004")
        assertEquals("That response came from inside the system. No one told me it could answer.", message?.text)
    }

    @Test fun messagesExpireAndCanBeDismissedByIdentity() {
        val manager = EchoDialogueManager(importantDurationMillis = 1_000)
        val first = manager.offer(DialogueEvent.LOW_INTEGRITY, 0)!!
        manager.dismiss(first.id + 1)
        assertEquals(first, manager.activeMessage)
        val next = manager.offer(DialogueEvent.PATTERN_TRANSITION, 1_000)
        assertNotNull(next)
        assertNotEquals(first.id, next!!.id)
    }
}
