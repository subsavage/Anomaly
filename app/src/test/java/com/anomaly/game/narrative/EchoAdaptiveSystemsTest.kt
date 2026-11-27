package com.anomaly.game.narrative

import com.anomaly.game.engine.GameSessionEngine
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class EchoAdaptiveSystemsTest {
    @Test fun strongPerformanceDoesNotTriggerAssistance() {
        val evaluator = EchoAssistanceEvaluator()
        repeat(10) { evaluator.record(correct = true, reactionMillis = 800) }
        assertEquals(1.0, evaluator.recentAccuracy!!, 0.0)
        assertEquals(AssistanceLevel.NONE, evaluator.eligibleHint(20_000))
    }

    @Test fun oneMistakeDoesNotTriggerAssistanceButThreeRepeatedErrorsDo() {
        val evaluator = EchoAssistanceEvaluator()
        evaluator.record(false, 1_000)
        assertEquals(AssistanceLevel.NONE, evaluator.eligibleHint(1_000))
        evaluator.record(true, 900)
        evaluator.record(true, 900)
        evaluator.record(false, 2_400)
        evaluator.record(false, 2_400)
        assertEquals(AssistanceLevel.NONE, evaluator.eligibleHint(4_000))
        evaluator.record(false, 2_400)
        assertEquals(AssistanceLevel.DIRECT, evaluator.eligibleHint(5_000))
    }

    @Test fun lowAccuracyWindowGetsSubtleHintThenHonorsCooldownAndSpacing() {
        val evaluator = EchoAssistanceEvaluator()
        listOf(true, true, true, true, false, true, false, false).forEach { evaluator.record(correct=it, reactionMillis=1_400) }
        assertEquals(.625, evaluator.recentAccuracy!!, 0.0)
        assertEquals(AssistanceLevel.SUBTLE, evaluator.eligibleHint(12_000))
        evaluator.markHintOffered(AssistanceLevel.SUBTLE, 12_000)
        assertEquals(AssistanceLevel.NONE, evaluator.eligibleHint(30_000)) // no immediate repeat
        repeat(8) { evaluator.record(correct = false, reactionMillis = 1_400) }
        assertEquals(AssistanceLevel.NONE, evaluator.eligibleHint(23_000)) // cooldown still applies
        assertEquals(AssistanceLevel.DIRECT, evaluator.eligibleHint(30_000))
    }

    @Test fun recoveredPerformanceStopsAssistance() {
        val evaluator = EchoAssistanceEvaluator()
        listOf(true, true, true, true, false, true, false, false).forEach { evaluator.record(correct=it, reactionMillis=1_200) }
        evaluator.markHintOffered(AssistanceLevel.SUBTLE, 10_000)
        repeat(8) { evaluator.record(correct = true, reactionMillis = 700) }
        assertTrue(evaluator.recentAccuracy!! >= .75)
        assertEquals(AssistanceLevel.NONE, evaluator.eligibleHint(40_000))
    }

    @Test fun progressionKeepsFirstRunsClearAndAddsDeterministicTiers() {
        assertFalse(InterferencePlan.forCompletedRuns(0).enabled)
        assertFalse(InterferencePlan.forCompletedRuns(1).enabled)
        val early = InterferencePlan.forCompletedRuns(2)
        assertEquals(1, early.maxEvents)
        assertEquals(10, early.firstWarningObject)
        val middle = InterferencePlan.forCompletedRuns(6)
        assertEquals(2, middle.maxEvents)
        assertEquals(7, middle.firstWarningObject)
        assertEquals(6, InterferencePlan.forCompletedRuns(10).firstWarningObject)
    }

    @Test fun warningIsDeferredForCriticalEventAndPrecedesTakeoverByTwoObjects() {
        val controller = EchoInterferenceController(InterferencePlan(firstWarningObject=4, maxEvents=1, intervalObjects=99))
        assertNull(controller.objectAvailable(4, deferForCriticalEvent=true))
        assertEquals(InterferenceStage.WARNING, controller.objectAvailable(5)!!.stage)
        assertEquals(InterferenceStage.WARNING_SECOND, controller.objectAvailable(6)!!.stage)
        assertEquals(CommunicationStatus.WARNING, controller.status)
        assertEquals(InterferenceStage.TAKEOVER, controller.objectAvailable(7)!!.stage)
        assertNull(controller.objectAvailable(8))
    }

    @Test fun takeoverSuppressesDialogueAndAssistanceForThreeResolvedObjectsThenRecovers() {
        val controller = EchoInterferenceController(InterferencePlan(firstWarningObject=1, maxEvents=1, intervalObjects=99, compromisedObjects=3))
        controller.objectAvailable(1)
        controller.objectAvailable(2)
        assertEquals(InterferenceStage.TAKEOVER, controller.objectAvailable(3)!!.stage)
        assertTrue(controller.suppressOrdinaryDialogue)
        assertTrue(controller.suppressAssistance)
        assertNull(controller.objectResolved())
        assertNull(controller.objectResolved())
        val recovery = controller.objectResolved()!!
        assertEquals(InterferenceStage.RECOVERY_FIRST, recovery.stage)
        assertEquals("Operator? Can you hear me?", recovery.text)
        assertEquals(InterferenceStage.RECOVERY_SECOND, controller.recoverySecond()!!.stage)
        controller.finishRecovery()
        assertEquals(CommunicationStatus.NORMAL, controller.status)
        assertFalse(controller.suppressOrdinaryDialogue)
        assertFalse(controller.suppressAssistance)
    }

    @Test fun interferenceSchedulingDoesNotMutateGameplayState() {
        val engine = GameSessionEngine(Random(7), decisionDeadlineMs=2_600) { 0L }
        engine.start()
        val original = engine.state
        val controller = EchoInterferenceController(InterferencePlan(firstWarningObject=1, maxEvents=1, intervalObjects=99))
        controller.objectAvailable(1)
        controller.objectAvailable(2)
        controller.objectAvailable(3)
        repeat(3) { controller.objectResolved() }
        assertEquals(original, engine.state)
    }

    @Test fun laterInterferenceWaitsUntilRecoveryAndItsConfiguredInterval() {
        val controller = EchoInterferenceController(InterferencePlan(firstWarningObject=2, maxEvents=2, intervalObjects=8))
        controller.objectAvailable(2)
        controller.objectAvailable(3)
        controller.objectAvailable(4)
        repeat(3) { controller.objectResolved() }
        controller.finishRecovery()
        assertEquals(1, controller.eventsStarted)
        assertNull(controller.objectAvailable(9))
        assertEquals(InterferenceStage.WARNING, controller.objectAvailable(10)!!.stage)
        assertEquals(1, controller.eventsStarted) // cannot overlap or count a second takeover early
    }
}
