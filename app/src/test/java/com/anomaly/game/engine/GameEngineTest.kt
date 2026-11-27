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
    @Test fun acceptedAnswerCannotScoreTwice() { var now=1_000L;val engine=GameSessionEngine(Random(1)){now};engine.start();val answer=engine.state.current!!.isAnomaly;engine.answer(answer);val first=engine.state.stats;engine.answer(answer);assertEquals(first,engine.state.stats) }
    @Test fun decisionDeadlineCostsOneLifeOnly() { var now=1_000L;val engine=GameSessionEngine(Random(2)){now};engine.start();now+=4_000;engine.tick();val state=engine.state;assertEquals(2,state.integrity);assertEquals(1,state.stats.total);engine.tick();assertEquals(1,state.stats.total) }
    @Test fun timerEndsAtSixtySeconds() { var now=10_000L;val engine=GameSessionEngine(Random(3)){now};engine.start();now+=60_000;engine.tick();assertTrue(engine.state.finished);assertEquals(0,engine.state.remainingMs) }
    @Test fun transitionsAreCountedAndRuleIsCapturedOnSpawn() { var now=0L;val engine=GameSessionEngine(Random(6)){now};engine.start();repeat(14){now+=200;val round=engine.state.current!!;engine.answer(round.isAnomaly)};assertTrue(engine.state.stats.transitions>=1);assertNotNull(engine.state.current?.ruleAtSpawn) }
}
