package com.anomaly.game.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GameEngineTest {
    @Test fun sequenceEvaluationUsesPatternPosition() {
        val rule = Rule(Family.SEQUENCE, "circle square triangle", sequence = listOf(Shape.CIRCLE, Shape.SQUARE, Shape.TRIANGLE))
        assertTrue(PatternEvaluator.follows(GameObject(Shape.SQUARE, Hue.BLUE, 1), rule, listOf(GameObject(Shape.CIRCLE, Hue.BLUE, 0))))
        assertFalse(PatternEvaluator.follows(GameObject(Shape.TRIANGLE, Hue.BLUE, 1), rule, listOf(GameObject(Shape.CIRCLE, Hue.BLUE, 0))))
    }
    @Test fun attributeEvaluationChecksEveryDefinedAttribute() {
        val rule = Rule(Family.ATTRIBUTE, "blue and two", hue = Hue.BLUE, dots = 2)
        assertTrue(PatternEvaluator.follows(GameObject(Shape.SQUARE, Hue.BLUE, 2), rule, emptyList()))
        assertFalse(PatternEvaluator.follows(GameObject(Shape.SQUARE, Hue.CORAL, 2), rule, emptyList()))
    }
    @Test fun relationshipEvaluationUsesPreviousObject() {
        val rule = Rule(Family.RELATIONSHIP, "increasing", relation = "dots+1")
        assertTrue(PatternEvaluator.follows(GameObject(Shape.CIRCLE, Hue.BLUE, 3), rule, listOf(GameObject(Shape.SQUARE, Hue.BLUE, 2))))
        assertFalse(PatternEvaluator.follows(GameObject(Shape.CIRCLE, Hue.BLUE, 0), rule, listOf(GameObject(Shape.SQUARE, Hue.BLUE, 2))))
    }
    @Test fun seededAnomaliesAlwaysViolateTheirRules() {
        val random = Random(42)
        val rules = listOf(Rule(Family.SEQUENCE, "seq", sequence=listOf(Shape.CIRCLE,Shape.SQUARE)), Rule(Family.ATTRIBUTE,"blue",hue=Hue.BLUE), Rule(Family.ATTRIBUTE,"two",dots=2), Rule(Family.RELATIONSHIP,"inc",relation="dots+1"))
        rules.forEach { rule -> repeat(100) { val history=if(rule.family==Family.SEQUENCE) listOf(GameObject(Shape.CIRCLE,Hue.BLUE,1)) else if(rule.family==Family.RELATIONSHIP) listOf(GameObject(Shape.CIRCLE,Hue.BLUE,1)) else emptyList(); val item=AnomalyGenerator.generate(random,rule,history); assertFalse(PatternEvaluator.follows(item,rule,history)) } }
    }
    @Test fun seededNormalsAlwaysFollowAndSeedRepeats() {
        val rule=Rule(Family.SEQUENCE,"seq",sequence=listOf(Shape.CIRCLE,Shape.SQUARE))
        val a=AnomalyGenerator.normal(Random(8),rule,emptyList()); val b=AnomalyGenerator.normal(Random(8),rule,emptyList())
        assertEquals(a,b); assertTrue(PatternEvaluator.follows(a,rule,emptyList()))
    }
    @Test fun scoringMultipliersFollowComboMilestones() { assertEquals(100,ScoringEngine.points(1)); assertEquals(120,ScoringEngine.points(3)); assertEquals(150,ScoringEngine.points(5)); assertEquals(200,ScoringEngine.points(8)) }
    @Test fun difficultyStaysBounded() { val engine=DifficultyEngine(); var d=Difficulty(); repeat(500){ engine.record(true,300); d=engine.adjust(d) }; assertTrue(d.speed<=1.6f); assertTrue(d.complexity<=2); repeat(500){engine.record(false,2000);d=engine.adjust(d)};assertTrue(d.speed>=.75f);assertTrue(d.complexity>=0) }
    @Test fun acceptedAnswerCannotScoreTwiceForTheSameObject() { var now=1_000L;val engine=GameSessionEngine(Random(1)){now};engine.start();val round=engine.state.current!!;assertNotNull(engine.answer(round.decisionId,round.isAnomaly));val first=engine.state;assertNull(engine.answer(round.decisionId,round.isAnomaly));assertEquals(first,engine.state) }
    @Test fun pauseFreezesRunAndDecisionClocksUntilResume() {
        var now = 1_000L
        val engine = GameSessionEngine(Random(7)) { now }
        engine.start()
        val round = engine.state.current!!
        val remainingBeforePause = engine.state.remainingMs
        engine.pause()
        now += 10_000L
        engine.tick()
        assertTrue(engine.state.paused)
        assertEquals(remainingBeforePause, engine.state.remainingMs)
        assertNull(engine.answer(round.decisionId, round.isAnomaly))
        engine.resume()
        assertFalse(engine.state.paused)
        assertEquals(round.spawnedAt + 10_000L, engine.state.current!!.spawnedAt)
        now += 100L
        assertNotNull(engine.answer(round.decisionId, round.isAnomaly))
        assertEquals(1, engine.state.stats.total)
    }
    @Test fun rapidDecisionForNewObjectIsAcceptedWithinPreviousGuardWindow() {
        var now=1_000L
        val engine=GameSessionEngine(Random(41)){now}
        engine.start()
        val first=engine.state.current!!
        assertNotNull(engine.answer(first.decisionId,first.isAnomaly))
        val second=engine.state.current!!
        assertNotEquals(first.decisionId,second.decisionId)
        now+=119L
        val resolution=engine.answer(second.decisionId,second.isAnomaly)
        assertNotNull(resolution)
        assertTrue(resolution!!.correct)
        assertEquals(2,engine.state.stats.total)
    }
    @Test fun staleCallbackCannotResolveTheNewCurrentObject() {
        var now=1_000L
        val engine=GameSessionEngine(Random(42)){now}
        engine.start()
        val oldRound=engine.state.current!!
        assertNotNull(engine.answer(oldRound.decisionId,oldRound.isAnomaly))
        val newRound=engine.state.current!!
        val before=engine.state
        now+=200L
        assertNull(engine.answer(oldRound.decisionId,newRound.isAnomaly))
        assertEquals(before,engine.state)
        assertEquals(1,engine.state.stats.total)
    }
    @Test fun acceptedResolutionCarriesActualResponseTimeAndClassification() {
        var now=1_000L
        val engine=GameSessionEngine(Random(43)){now}
        engine.start()
        val round=engine.state.current!!
        now+=375L
        val resolution=engine.answer(round.decisionId,round.isAnomaly)!!
        assertTrue(resolution.correct)
        assertFalse(resolution.timedOut)
        assertEquals(375L,resolution.responseTimeMillis)
    }
    @Test fun incorrectAnsweredDecisionCarriesItsActualResponseTime() {
        var now=2_000L
        val engine=GameSessionEngine(Random(45)){now}
        engine.start()
        val round=engine.state.current!!
        now+=425L
        val resolution=engine.answer(round.decisionId,!round.isAnomaly)!!
        assertFalse(resolution.correct)
        assertFalse(resolution.timedOut)
        assertEquals(425L,resolution.responseTimeMillis)
    }
    @Test fun decisionDeadlineCostsOneLifeOnly() { var now=1_000L;val engine=GameSessionEngine(Random(2)){now};engine.start();now+=4_000;engine.tick();val state=engine.state;assertEquals(2,state.integrity);assertEquals(1,state.stats.total);engine.tick();assertEquals(1,state.stats.total) }
    @Test fun timerEndsAtSixtySeconds() { var now=10_000L;val engine=GameSessionEngine(Random(3)){now};engine.start();now+=60_000;engine.tick();assertTrue(engine.state.finished);assertEquals(0,engine.state.remainingMs) }
    @Test fun transitionsAreCountedAndRuleIsCapturedOnSpawn() { var now=0L;val engine=GameSessionEngine(Random(6)){now};engine.start();repeat(14){now+=200;val round=engine.state.current!!;engine.answer(round.decisionId,round.isAnomaly)};assertTrue(engine.state.stats.transitions>=1);assertNotNull(engine.state.current?.ruleAtSpawn) }

    @Test fun transitionWarningPrecedesBoundaryAndPresentedObjectKeepsItsRule() {
        var now=0L
        val engine=GameSessionEngine(Random(61)){now}
        engine.start()
        repeat(13) { now+=200; val round=engine.state.current!!; engine.answer(round.decisionId,round.isAnomaly) }
        assertEquals(13,engine.state.stats.total)
        assertEquals("SYSTEM ALERT · PATTERN SHIFT NEXT",engine.state.message)
        val warnedRound=engine.state.current!!
        val ruleDuringWarning=warnedRound.ruleAtSpawn
        now+=200
        engine.answer(warnedRound.decisionId,warnedRound.isAnomaly)
        assertEquals(14,engine.state.stats.total)
        assertEquals(1,engine.state.stats.transitions)
        assertEquals(ruleDuringWarning,warnedRound.ruleAtSpawn)
        assertEquals(engine.state.rule,engine.state.current?.ruleAtSpawn)
    }

    @Test fun finalWrongDecisionKeepsExactObjectRuleAndPlayerSelection() {
        var now=1_000L
        val engine=GameSessionEngine(Random(12),clock={now})
        engine.start()
        repeat(3) {
            now+=200
            val round=engine.state.current!!
            val history=engine.state.history
            engine.answer(round.decisionId,!round.isAnomaly)
            if(it==2) {
                val final=engine.state.decisionHistory.last()
                assertEquals(RunEndReason.INTEGRITY_DEPLETED,engine.state.endReason)
                assertEquals(3,engine.state.missedCalls.size)
                assertEquals(round.item,final.item)
                assertEquals(round.ruleAtSpawn,final.activeRule)
                assertEquals(round.ruleAtSpawn.playerInstruction(history),final.activeRuleDescription)
                assertEquals(round.isAnomaly,final.correctAnomaly)
                assertEquals(!round.isAnomaly,final.playerAnomaly)
                assertFalse(final.wasCorrect)
                assertFalse(final.timedOut)
                assertFalse(final.explanation.isBlank())
            }
        }
    }

    @Test fun deadlineMissIsRecordedAsTimeoutNotAPlayerClassification() {
        var now=0L
        val engine=GameSessionEngine(Random(19),clock={now})
        engine.start()
        repeat(3) { index ->
            val round=engine.state.current!!
            now=round.spawnedAt+round.deadlineMs+1
            val resolution=engine.tick()
            assertNotNull(resolution)
            assertTrue(resolution!!.timedOut)
            assertNull(resolution.responseTimeMillis)
            assertEquals(index + 1,engine.state.stats.total)
        }
        val final=engine.state.decisionHistory.last()
        assertEquals(RunEndReason.INTEGRITY_DEPLETED,engine.state.endReason)
        assertEquals(3,engine.state.missedCalls.size)
        assertTrue(final.timedOut)
        assertNull(final.playerAnomaly)
        assertFalse(final.wasCorrect)
    }

    @Test fun multipleIncorrectDecisionsAreAvailableAsMissedCalls() {
        var now = 1_000L
        val engine = GameSessionEngine(Random(71)) { now }
        engine.start()
        repeat(2) {
            now += 200L
            val round = engine.state.current!!
            engine.answer(round.decisionId, !round.isAnomaly)
        }
        assertEquals(2, engine.state.missedCalls.size)
        assertTrue(engine.state.missedCalls.all { !it.wasCorrect && !it.timedOut && it.playerAnomaly != null })
    }

    @Test fun multipleTimeoutsAreAvailableAsMissedCalls() {
        var now = 0L
        val engine = GameSessionEngine(Random(72)) { now }
        engine.start()
        repeat(2) {
            val round = engine.state.current!!
            now = round.spawnedAt + round.deadlineMs + 1L
            engine.tick()
        }
        assertEquals(2, engine.state.missedCalls.size)
        assertTrue(engine.state.missedCalls.all { it.timedOut && !it.wasCorrect && it.playerAnomaly == null })
    }

    @Test fun incorrectAnswersAndTimeoutsShareTheMissedCallHistory() {
        var now = 0L
        val engine = GameSessionEngine(Random(73)) { now }
        engine.start()
        now += 200L
        val wrong = engine.state.current!!
        engine.answer(wrong.decisionId, !wrong.isAnomaly)
        val timedOut = engine.state.current!!
        now = timedOut.spawnedAt + timedOut.deadlineMs + 1L
        engine.tick()
        assertEquals(2, engine.state.missedCalls.size)
        assertFalse(engine.state.missedCalls[0].timedOut)
        assertTrue(engine.state.missedCalls[1].timedOut)
    }

    @Test fun correctDecisionsAreExcludedFromMissedCalls() {
        var now = 0L
        val engine = GameSessionEngine(Random(74)) { now }
        engine.start()
        repeat(5) {
            now += 200L
            val round = engine.state.current!!
            engine.answer(round.decisionId, round.isAnomaly)
        }
        assertEquals(5, engine.state.decisionHistory.size)
        assertTrue(engine.state.decisionHistory.all { it.wasCorrect })
        assertTrue(engine.state.missedCalls.isEmpty())
    }

    @Test fun missedCallKeepsItsRuleSnapshotAcrossProtocolTransitions() {
        var now = 0L
        val engine = GameSessionEngine(Random(75)) { now }
        engine.start()
        val initialRound = engine.state.current!!
        val initialRule = initialRound.ruleAtSpawn.copy(sequence = initialRound.ruleAtSpawn.sequence.toList())
        now += 200L
        engine.answer(initialRound.decisionId, !initialRound.isAnomaly)
        repeat(13) {
            now += 200L
            val round = engine.state.current!!
            engine.answer(round.decisionId, round.isAnomaly)
        }
        assertTrue(engine.state.stats.transitions > 0)
        val historical = engine.state.missedCalls.first()
        assertEquals(initialRule, historical.activeRule)
        assertEquals(initialRule.id, historical.activeRuleId)
        assertFalse(historical.activeRuleDescription.isBlank())
    }

    @Test fun missedCallReviewIsAvailableAfterTimerExpiration() {
        var now = 10_000L
        val engine = GameSessionEngine(Random(76)) { now }
        engine.start()
        val wrong = engine.state.current!!
        now += 200L
        engine.answer(wrong.decisionId, !wrong.isAnomaly)
        now = 70_000L
        engine.tick()
        assertEquals(RunEndReason.TIME_EXPIRED, engine.state.endReason)
        assertEquals(2, engine.state.missedCalls.size)
        assertTrue(engine.state.missedCalls.last().timedOut)
        assertEquals(1, engine.state.stats.total)
    }

    @Test fun cleanRunHasAnEmptyMissedCallReview() {
        var now = 0L
        val engine = GameSessionEngine(Random(77)) { now }
        engine.start()
        repeat(3) {
            now += 200L
            val round = engine.state.current!!
            engine.answer(round.decisionId, round.isAnomaly)
        }
        assertTrue(engine.state.missedCalls.isEmpty())
    }

    @Test fun recentTransitionContextIsAttachedToDecisionSnapshot() {
        var now=0L
        val engine=GameSessionEngine(Random(28)){now}
        engine.start()
        repeat(14) { now+=200; val round=engine.state.current!!; engine.answer(round.decisionId,round.isAnomaly) }
        val postTransition=engine.state.current!!
        assertNotNull(postTransition.previousRule)
        now+=200
        engine.answer(postTransition.decisionId,!postTransition.isAnomaly)
        val decision=engine.state.decisionHistory.last()
        assertTrue(decision.recentTransition)
        assertNotNull(decision.previousRule)
        assertEquals(postTransition.ruleAtSpawn,decision.newRule)
        assertNotNull(decision.previousRuleDescription)
    }

    @Test fun restartClearsDecisionSnapshotsAndTransitionContext() {
        var now=0L
        val engine=GameSessionEngine(Random(3)){now}
        engine.start()
        repeat(2) { now+=200; val r=engine.state.current!!; engine.answer(r.decisionId,!r.isAnomaly) }
        assertEquals(2,engine.state.decisionHistory.size)
        assertEquals(2,engine.state.missedCalls.size)
        engine.start()
        assertTrue(engine.state.decisionHistory.isEmpty())
        assertTrue(engine.state.missedCalls.isEmpty())
        assertNull(engine.state.endReason)
        assertNull(engine.state.current?.previousRule)
    }

    @Test fun losingOnFirstObjectAfterTransitionStillCountsTheProtocolChange() {
        var now=0L
        val engine=GameSessionEngine(Random(51)){now}
        engine.start()
        repeat(2) { now+=200; val round=engine.state.current!!; engine.answer(round.decisionId,!round.isAnomaly) }
        repeat(12) { now+=200; val round=engine.state.current!!; engine.answer(round.decisionId,round.isAnomaly) }
        assertEquals(1,engine.state.stats.transitions)
        assertEquals(1,engine.state.integrity)
        val firstUnderNewRule=engine.state.current!!
        now+=200
        engine.answer(firstUnderNewRule.decisionId,!firstUnderNewRule.isAnomaly)
        assertEquals(1,engine.state.stats.transitions)
        assertEquals(RunEndReason.INTEGRITY_DEPLETED,engine.state.endReason)
    }

    @Test fun successfulFirstObjectAfterTransitionStillCountsTheProtocolChange() {
        var now=0L
        val engine=GameSessionEngine(Random(52)){now}
        engine.start()
        repeat(14) { now+=200; val round=engine.state.current!!; engine.answer(round.decisionId,round.isAnomaly) }
        val firstUnderNewRule=engine.state.current!!
        now+=200
        engine.answer(firstUnderNewRule.decisionId,firstUnderNewRule.isAnomaly)
        assertEquals(1,engine.state.stats.transitions)
        assertEquals(15,engine.state.stats.correct)
    }

    @Test fun protocolChangeCounterCountsMultipleChangesInOneRun() {
        var now=0L
        val engine=GameSessionEngine(Random(53)){now}
        engine.start()
        repeat(28) { now+=200; val round=engine.state.current!!; engine.answer(round.decisionId,round.isAnomaly) }
        assertEquals(2,engine.state.stats.transitions)
    }

    @Test fun openingTutorialUsesValidExamplesForTheFirstRunRuleFamily() {
        assertEquals(Family.ATTRIBUTE,OpeningTutorial.rule.family)
        repeat(100) { seed ->
            assertEquals(Family.ATTRIBUTE,PatternGenerator.next(Random(seed),level=0).family)
        }
        assertTrue(PatternEvaluator.follows(OpeningTutorial.normalExample,OpeningTutorial.rule,emptyList()))
        assertFalse(PatternEvaluator.follows(OpeningTutorial.anomalyExample,OpeningTutorial.rule,emptyList()))
    }

    @Test fun explanationsUseTheSpecificRuleAndObjectAttributes() {
        val sequence=Rule(Family.SEQUENCE,"sequence",sequence=listOf(Shape.CIRCLE,Shape.SQUARE))
        val wrongShape=DecisionExplanation.describe(GameObject(Shape.TRIANGLE,Hue.BLUE,1),sequence,listOf(GameObject(Shape.CIRCLE,Hue.BLUE,1)))
        assertEquals("Expected Square; this object was Triangle.",wrongShape)
        val color=Rule(Family.ATTRIBUTE,"blue",hue=Hue.BLUE)
        val wrongColor=DecisionExplanation.describe(GameObject(Shape.CIRCLE,Hue.CORAL,1),color,emptyList())
        assertTrue(wrongColor.contains("Color was coral"))
        assertTrue(wrongColor.contains("required blue"))
        val relation=Rule(Family.RELATIONSHIP,"one more dot",relation="dots+1")
        val wrongCount=DecisionExplanation.describe(GameObject(Shape.CIRCLE,Hue.BLUE,0),relation,listOf(GameObject(Shape.SQUARE,Hue.GREEN,2)))
        assertTrue(wrongCount.contains("Expected 3 dots"))
        assertTrue(wrongCount.contains("this had 0 dots"))
    }
}
