package com.anomaly.game.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RuleInstructionTest {
    @Test fun sequenceRulesExplainTheLoopAndNameTheNextShape() {
        val rule = Rule(Family.SEQUENCE, "legacy", sequence = listOf(Shape.CIRCLE, Shape.TRIANGLE))
        assertEquals("Repeating order: Circle → Triangle", rule.playerInstruction(emptyList()))
        assertEquals("NEXT SHAPE: Circle", rule.focusCue(emptyList()))
        val history = listOf(GameObject(Shape.CIRCLE, Hue.BLUE, 1))
        assertEquals("NEXT SHAPE: Triangle", rule.focusCue(history))
        assertEquals("NEXT SHAPE: Circle", rule.focusCue(history + GameObject(Shape.TRIANGLE, Hue.BLUE, 0)))
    }

    @Test fun simpleAttributeRulesKeepTheirDirectWording() {
        val colorRule = Rule(Family.ATTRIBUTE, "Every object is red", hue = Hue.CORAL)
        assertEquals("Every object is red", colorRule.playerInstruction(emptyList()))
        assertEquals("Every object is red", colorRule.focusCue(emptyList()))
        val dotRule = Rule(Family.ATTRIBUTE, "Exactly one dot", dots = 1)
        assertEquals("Exactly one dot", dotRule.playerInstruction(emptyList()))
    }

    @Test fun patternSequencesUnlockAtAdvancedDifficulty() {
        val opening = buildSet { val random = Random(10); repeat(100) { add(PatternGenerator.next(random, 0).family) } }
        assertEquals(setOf(Family.ATTRIBUTE), opening)
        val streak = buildSet { val random = Random(20); repeat(100) { add(PatternGenerator.next(random, 1).family) } }
        assertTrue(Family.SEQUENCE in streak)
        assertFalse(Family.RELATIONSHIP in streak)
        val advanced = buildSet { val random = Random(30); repeat(100) { add(PatternGenerator.next(random, 2).family) } }
        assertTrue(Family.RELATIONSHIP in advanced)
    }
}
