package com.anomaly.game.engine

import kotlin.math.max
import kotlin.random.Random

enum class Shape { CIRCLE, SQUARE, TRIANGLE }
enum class Hue { CORAL, BLUE, YELLOW, GREEN }
data class GameObject(val shape: Shape, val hue: Hue, val dots: Int, val size: Int = 1)
enum class Family { SEQUENCE, ATTRIBUTE, RELATIONSHIP }
data class Rule(val family: Family, val label: String, val sequence: List<Shape> = emptyList(), val hue: Hue? = null, val dots: Int? = null, val relation: String? = null)
data class Difficulty(val speed: Float = 1f, val complexity: Int = 0, val subtlety: Int = 0, val transitionRate: Int = 0)
data class RoundObject(val item: GameObject, val isAnomaly: Boolean, val ruleAtSpawn: Rule, val spawnedAt: Long, val deadlineMs: Long, val previousRule: Rule? = null)
enum class RunEndReason { INTEGRITY_DEPLETED, TIME_EXPIRED }
data class DecisionContext(
    val objectOrdinal: Int,
    val item: GameObject,
    val activeRule: Rule,
    val activeRuleDescription: String,
    val correctAnomaly: Boolean,
    val playerAnomaly: Boolean?,
    val wasCorrect: Boolean,
    val timedOut: Boolean,
    val lateResponse: Boolean,
    val explanation: String,
    val recentTransition: Boolean,
    val previousRule: Rule? = null,
    val newRule: Rule? = null,
    val previousRuleDescription: String? = null,
    val newRuleDescription: String? = null
)
data class Stats(val score: Int = 0, val correct: Int = 0, val total: Int = 0, val combo: Int = 0, val bestCombo: Int = 0, val transitions: Int = 0, val reactionTotal: Long = 0, val errors: Int = 0)
data class GameState(val running: Boolean = false, val finished: Boolean = false, val remainingMs: Long = 60_000, val integrity: Int = 3, val rule: Rule? = null, val history: List<GameObject> = emptyList(), val current: RoundObject? = null, val stats: Stats = Stats(), val difficulty: Difficulty = Difficulty(), val message: String = "", val lastCorrect: Boolean? = null, val best: Int = 0, val tutorialStep: Int = 0, val settings: Boolean = false, val decisionHistory: List<DecisionContext> = emptyList(), val endReason: RunEndReason? = null)
data class GameResult(val score: Int, val best: Int, val accuracy: Int, val correct: Int, val bestCombo: Int, val transitions: Int, val newBest: Boolean)

object ObjectGenerator {
    fun next(random: Random, shape: Shape? = null, hue: Hue? = null, dots: Int? = null) = GameObject(shape ?: Shape.entries.random(random), hue ?: Hue.entries.random(random), dots ?: random.nextInt(4), random.nextInt(1, 4))
}

object PatternGenerator {
    fun next(random: Random, level: Int): Rule {
        if (level == 0) return attribute(random, level)
        return when (random.nextInt(if (level >= 2) 3 else 2)) {
            0 -> sequence(random)
            1 -> attribute(random, level)
            else -> Rule(Family.RELATIONSHIP, "Dot count increases by one", relation = "dots+1")
        }
    }

    private fun sequence(random: Random): Rule {
        val candidates = listOf(
            listOf(Shape.CIRCLE, Shape.SQUARE, Shape.TRIANGLE),
            listOf(Shape.CIRCLE, Shape.TRIANGLE),
            listOf(Shape.SQUARE, Shape.SQUARE, Shape.CIRCLE)
        )
        val sequence = candidates.random(random)
        val label = sequence.joinToString("  ›  ") { it.displayName() }
        return Rule(Family.SEQUENCE, label, sequence = sequence)
    }

    private fun attribute(random: Random, level: Int): Rule = if (level >= 1 && random.nextBoolean()) {
        val hue = Hue.entries.random(random); val dots = random.nextInt(4)
        Rule(Family.ATTRIBUTE, "${hue.name.lowercase()} · exactly ${dots.dotLabel().lowercase()}", hue = hue, dots = dots)
    } else if (random.nextBoolean()) {
        val hue = Hue.entries.random(random); Rule(Family.ATTRIBUTE, "Every object is ${hue.name.lowercase()}", hue = hue)
    } else {
        val dots = random.nextInt(4); Rule(Family.ATTRIBUTE, "Exactly ${dots.dotLabel().lowercase()}", dots = dots)
    }
}
object PatternEvaluator {
    fun follows(item: GameObject, rule: Rule, history: List<GameObject>): Boolean = when (rule.family) {
        Family.SEQUENCE -> rule.sequence.isNotEmpty() && item.shape == rule.sequence[history.size % rule.sequence.size]
        Family.ATTRIBUTE -> (rule.hue == null || item.hue == rule.hue) && (rule.dots == null || item.dots == rule.dots)
        Family.RELATIONSHIP -> when (rule.relation) { "dots+1" -> history.lastOrNull()?.let { item.dots == (it.dots + 1) % 4 } ?: true; else -> false }
    }
}

private fun Shape.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)
private fun Int.dotLabel(): String = "$this ${if (this == 1) "DOT" else "DOTS"}"

object DecisionExplanation {
    fun describe(item: GameObject, rule: Rule, history: List<GameObject>): String {
        val follows = PatternEvaluator.follows(item, rule, history)
        return when (rule.family) {
            Family.SEQUENCE -> {
                val expected = rule.sequence.getOrNull(history.size % rule.sequence.size.coerceAtLeast(1))
                if (rule.sequence.isEmpty()) "The active sequence did not define a next shape."
                else if (follows) "${item.shape.displayName()} was next in the repeating sequence."
                else "Expected ${expected?.displayName() ?: "the next shape"}; this object was ${item.shape.displayName()}."
            }
            Family.ATTRIBUTE -> {
                val mismatches = buildList {
                    if (rule.hue != null && item.hue != rule.hue) add("color was ${item.hue.name.lowercase()}, but the rule required ${rule.hue.name.lowercase()}")
                    if (rule.dots != null && item.dots != rule.dots) add("it had ${item.dots.dotLabel().lowercase()}, but the rule required ${rule.dots.dotLabel().lowercase()}")
                }
                if (follows) when {
                    rule.hue != null && rule.dots != null -> "Its color and dot count matched the active rule."
                    rule.hue != null -> "Its color matched the active rule."
                    rule.dots != null -> "Its dot count matched the active rule."
                    else -> "It matched the active rule."
                } else mismatches.joinToString("; ").replaceFirstChar(Char::uppercase) + "."
            }
            Family.RELATIONSHIP -> {
                val previous = history.lastOrNull()
                if (rule.relation != "dots+1") "The active protocol had no supported dot relationship."
                else if (previous == null) "There was no previous object to compare; the first object follows this rule."
                else {
                    val expected = (previous.dots + 1) % 4
                    if (follows && previous.dots == 3) "It had zero dots after three, following the protocol's wraparound."
                    else if (follows) "It had one more dot than the previous object."
                    else "Expected ${expected.dotLabel().lowercase()} after the previous object; this had ${item.dots.dotLabel().lowercase()}."
                }
            }
        }
    }
}

/** Shape loops are always identified as repeating and expose the next expected shape. Other rules keep their established direct wording. */
fun Rule.playerInstruction(history: List<GameObject>): String = if (family == Family.SEQUENCE) {
    "Repeating order: ${sequence.joinToString(" → ") { it.displayName() }}"
} else label

fun Rule.focusCue(history: List<GameObject>): String = if (family == Family.SEQUENCE && sequence.isNotEmpty()) {
    "NEXT SHAPE: ${sequence[history.size % sequence.size].displayName()}"
} else label
object AnomalyGenerator {
    fun generate(random: Random, rule: Rule, history: List<GameObject>): GameObject {
        repeat(20) {
            val candidate = when (rule.family) {
                Family.SEQUENCE -> ObjectGenerator.next(random, shape = Shape.entries.filter { it != rule.sequence[history.size % rule.sequence.size] }.random(random))
                Family.ATTRIBUTE -> when { rule.hue != null -> ObjectGenerator.next(random, hue = Hue.entries.filter { it != rule.hue }.random(random)); else -> ObjectGenerator.next(random, dots = (0..3).filter { it != rule.dots }.random(random)) }
                Family.RELATIONSHIP -> ObjectGenerator.next(random, dots = history.lastOrNull()?.let { (it.dots + 2) % 4 } ?: 2)
            }
            if (!PatternEvaluator.follows(candidate, rule, history)) return candidate
        }
        error("Could not generate a valid anomaly")
    }
    fun normal(random: Random, rule: Rule, history: List<GameObject>): GameObject = when (rule.family) {
        Family.SEQUENCE -> ObjectGenerator.next(random, shape = rule.sequence[history.size % rule.sequence.size])
        Family.ATTRIBUTE -> ObjectGenerator.next(random, hue = rule.hue, dots = rule.dots)
        Family.RELATIONSHIP -> ObjectGenerator.next(random, dots = history.lastOrNull()?.let { (it.dots + 1) % 4 } ?: 1)
    }.also { check(PatternEvaluator.follows(it, rule, history)) }
}

object ScoringEngine {
    fun multiplier(combo: Int): Double = when { combo >= 8 -> 2.0; combo >= 5 -> 1.5; combo >= 3 -> 1.2; else -> 1.0 }
    fun points(comboAfter: Int) = (100 * multiplier(comboAfter)).toInt()
}

class DifficultyEngine {
    private val window = ArrayDeque<Pair<Boolean, Long>>()
    private var strongStreak = 0
    fun record(correct: Boolean, reactionMs: Long) {
        window.addLast(correct to reactionMs); if (window.size > 10) window.removeFirst()
        if (correct && reactionMs < 900) strongStreak++ else if (!correct) strongStreak = 0
    }
    fun adjust(current: Difficulty): Difficulty {
        val accuracy = if (window.isEmpty()) 1.0 else window.count { it.first }.toDouble() / window.size
        if (accuracy < .6) return Difficulty((current.speed - .08f).coerceAtLeast(.75f), (current.complexity - 1).coerceAtLeast(0), (current.subtlety - 1).coerceAtLeast(0), (current.transitionRate - 1).coerceAtLeast(0))
        if (strongStreak >= 6) {
            strongStreak = 0
            return when {
                current.complexity < 2 -> current.copy(complexity = current.complexity + 1)
                current.subtlety < 2 -> current.copy(subtlety = current.subtlety + 1)
                current.transitionRate < 2 -> current.copy(transitionRate = current.transitionRate + 1)
                else -> current.copy(speed = (current.speed + .06f).coerceAtMost(1.6f))
            }
        }
        return current
    }
}

class GameSessionEngine(private val random: Random = Random.Default, private val decisionDeadlineMs: Long = 2600, private val clock: () -> Long = System::currentTimeMillis) {
    var state = GameState(); private set
    private var difficulty = DifficultyEngine()
    private var startedAt = 0L
    private var lastSpawn = 0L
    private var lastAnomalyFamily: Family? = null
    private var lastWasAnomaly = false
    private var lastResolvedAt = Long.MIN_VALUE
    private var transitionAtTotal: Int? = null
    private var transitionPreviousRule: Rule? = null
    fun start(best: Int = state.best) {
        startedAt = clock(); difficulty = DifficultyEngine(); lastAnomalyFamily = null; lastWasAnomaly = false; lastResolvedAt = Long.MIN_VALUE; transitionAtTotal = null; transitionPreviousRule = null; state = GameState(running = true, best = best, rule = PatternGenerator.next(random, 0)); spawn()
    }
    private fun spawn() {
        if (!state.running) return
        val now = clock(); lastSpawn = now
        val elapsed = 60_000 - (now - startedAt)
        if (elapsed <= 0) { finish(RunEndReason.TIME_EXPIRED); return }
        val switchEvery = when { state.stats.transitions == 0 -> 14; state.difficulty.transitionRate > 0 -> (12 - state.difficulty.transitionRate * 2).coerceAtLeast(7); else -> 14 }
        if (state.stats.total > 0 && state.stats.total % switchEvery == 0) {
            transitionPreviousRule = state.rule
            transitionAtTotal = state.stats.total
            state = state.copy(rule = PatternGenerator.next(random, state.difficulty.complexity), stats = state.stats.copy(transitions = state.stats.transitions + 1), message = "PATTERN SHIFT")
        }
        if (state.stats.total > 0 && (state.stats.total + 1) % switchEvery == 0) state = state.copy(message = "SYSTEM ALERT · PATTERN SHIFT NEXT")
        val rule = state.rule ?: PatternGenerator.next(random, 0)
        val anomalyRate = (0.28f - 0.03f * state.difficulty.subtlety).coerceAtLeast(0.18f)
        val anomaly = random.nextFloat() < anomalyRate && !(lastWasAnomaly && rule.family == lastAnomalyFamily) && !(rule.family == Family.RELATIONSHIP && state.history.isEmpty())
        val item = if (anomaly) AnomalyGenerator.generate(random, rule, state.history) else AnomalyGenerator.normal(random, rule, state.history)
        lastWasAnomaly = anomaly
        if (anomaly) lastAnomalyFamily = rule.family
        val recentPreviousRule = transitionAtTotal?.let { total -> if (state.stats.total - total in 0..2) transitionPreviousRule else null }
        state = state.copy(remainingMs = elapsed, current = RoundObject(item, anomaly, rule, now, (decisionDeadlineMs / state.difficulty.speed).toLong().coerceAtLeast(1100), recentPreviousRule))
    }
    fun tick() {
        if (!state.running) return
        val now = clock(); val elapsed = now - startedAt
        if (elapsed >= 60_000) { finish(RunEndReason.TIME_EXPIRED); return }
        val current = state.current
        if (current != null && now - current.spawnedAt >= current.deadlineMs) resolve(null, now)
        else state = state.copy(remainingMs = 60_000 - elapsed)
    }
    fun answer(anomaly: Boolean) = resolve(anomaly, clock())
    private fun resolve(answer: Boolean?, now: Long) {
        val round = state.current ?: return
        if (!state.running || (lastResolvedAt != Long.MIN_VALUE && now - lastResolvedAt < 120)) { return }
        lastResolvedAt = now
        val responseTime = now - round.spawnedAt
        val lateResponse = answer != null && responseTime > round.deadlineMs
        val followsRule = PatternEvaluator.follows(round.item, round.ruleAtSpawn, state.history)
        val correctAnomaly = !followsRule
        val correct = answer != null && answer == round.isAnomaly && !lateResponse
        val context = DecisionContext(
            objectOrdinal = state.stats.total + 1,
            item = round.item,
            activeRule = round.ruleAtSpawn,
            activeRuleDescription = round.ruleAtSpawn.playerInstruction(state.history),
            correctAnomaly = correctAnomaly,
            playerAnomaly = answer,
            wasCorrect = correct,
            timedOut = answer == null,
            lateResponse = lateResponse,
            explanation = if(lateResponse) "The response arrived after this object's decision window expired." else DecisionExplanation.describe(round.item, round.ruleAtSpawn, state.history),
            recentTransition = round.previousRule != null,
            previousRule = round.previousRule,
            newRule = round.ruleAtSpawn.takeIf { round.previousRule != null },
            previousRuleDescription = round.previousRule?.playerInstruction(state.history),
            newRuleDescription = round.ruleAtSpawn.playerInstruction(state.history).takeIf { round.previousRule != null }
        )
        val prev = state.stats; val combo = if (correct) prev.combo + 1 else 0
        val newStats = prev.copy(score = prev.score + if (correct) ScoringEngine.points(combo) else 0, correct = prev.correct + if (correct) 1 else 0, total = prev.total + 1, combo = combo, bestCombo = max(prev.bestCombo, combo), reactionTotal = prev.reactionTotal + if (correct) now - round.spawnedAt else 0, errors = prev.errors + if (correct) 0 else 1)
        difficulty.record(correct, if (correct) now - round.spawnedAt else round.deadlineMs)
        val nextDifficulty = difficulty.adjust(state.difficulty)
        val newHistory = (state.history + round.item)
        val life = state.integrity - if (correct) 0 else 1
        state = state.copy(stats = newStats, integrity = life, current = null, history = newHistory, difficulty = nextDifficulty, message = if (correct && combo == 3) "COMBO x1.2" else if (correct && combo == 5) "COMBO x1.5" else if (correct && combo >= 8) "SYSTEM OVERRIDE" else if (!correct) "SIGNAL LOST" else "+${ScoringEngine.points(combo)}", lastCorrect = correct, decisionHistory = (state.decisionHistory + context).takeLast(64))
        when {
            life <= 0 -> finish(RunEndReason.INTEGRITY_DEPLETED)
            now - startedAt >= 60_000 -> finish(RunEndReason.TIME_EXPIRED)
            else -> spawn()
        }
    }
    private fun finish(reason: RunEndReason) { state = state.copy(running = false, finished = true, remainingMs = (60_000 - (clock() - startedAt)).coerceAtLeast(0), current = null, endReason = reason) }
    fun result(personalBest: Int): GameResult { val s = state.stats; val scoreBest = max(personalBest, s.score); return GameResult(s.score, scoreBest, if (s.total == 0) 0 else (100.0 * s.correct / s.total).toInt(), s.correct, s.bestCombo, s.transitions, s.score > personalBest) }
}
