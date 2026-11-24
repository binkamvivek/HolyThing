package com.holything.app.domain.ranking

import com.holything.app.data.local.room.entity.BetaAffinityEntity
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Dimensions tracked in the long-term Beta distribution profile.
 */
object BetaDimension {
    const val TOPIC = "topic"
    const val FORMAT = "format"
    const val TONE = "tone"
    const val LENGTH = "length"
}

data class BetaParams(
    val alpha: Float = 1f,
    val beta: Float = 1f
) {
    val totalTrials: Float
        get() = (alpha - 1f).coerceAtLeast(0f) + (beta - 1f).coerceAtLeast(0f)

    fun mean(): Float = alpha / (alpha + beta)

    /**
     * Draws a sample from Beta(alpha, beta) using Gamma variates:
     * X ~ Gamma(alpha, 1), Y ~ Gamma(beta, 1), then X / (X + Y).
     */
    fun sample(random: Random): Float {
        val x = sampleGamma(alpha.toDouble().coerceAtLeast(1e-3), random)
        val y = sampleGamma(beta.toDouble().coerceAtLeast(1e-3), random)
        val sum = x + y
        return if (sum <= 0.0) mean() else (x / sum).toFloat().coerceIn(0f, 1f)
    }

    private fun sampleGamma(shape: Double, random: Random): Double {
        if (shape < 1.0) {
            val u = random.nextDouble().coerceAtLeast(1e-12)
            return sampleGamma(shape + 1.0, random) * Math.pow(u, 1.0 / shape)
        }
        // Marsaglia and Tsang's method for shape >= 1
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            val z = nextGaussian(random)
            val v = (1.0 + c * z).let { it * it * it }
            if (v <= 0.0) continue
            val u = random.nextDouble().coerceAtLeast(1e-12)
            if (u < 1.0 - 0.0331 * (z * z) * (z * z)) {
                return d * v
            }
            if (ln(u) < 0.5 * z * z + d * (1.0 - v + ln(v))) {
                return d * v
            }
        }
    }

    private fun nextGaussian(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}

/**
 * Immutable snapshot of the user's long-term Beta affinities across topic, format, tone, and length.
 */
data class LongTermBetaProfile(
    val entries: Map<Pair<String, String>, BetaParams> = emptyMap()
) {
    fun getParams(dimension: String, value: String): BetaParams {
        return entries[dimension to value.lowercase().trim()] ?: BetaParams()
    }

    fun sample(dimension: String, value: String, random: Random): Float {
        return getParams(dimension, value).sample(random)
    }

    fun mean(dimension: String, value: String): Float {
        return getParams(dimension, value).mean()
    }

    fun hasSeenDimensionValue(dimension: String, value: String): Boolean {
        val params = entries[dimension to value.lowercase().trim()] ?: return false
        return params.totalTrials > 0f
    }

    fun totalInteractionsCount(): Float {
        return entries
            .filterKeys { it.first == BetaDimension.FORMAT }
            .values
            .sumOf { it.totalTrials.toDouble() }
            .toFloat()
    }

    fun topValue(dimension: String): String? {
        return entries
            .filterKeys { it.first == dimension }
            .maxByOrNull { it.value.mean() * (1f + it.value.totalTrials.coerceAtMost(10f) * 0.05f) }
            ?.key
            ?.second
    }

    companion object {
        fun fromEntities(entities: List<BetaAffinityEntity>): LongTermBetaProfile {
            val map = entities.associate { entity ->
                (entity.dimension to entity.value.lowercase().trim()) to
                    BetaParams(alpha = entity.alpha.coerceAtLeast(1f), beta = entity.beta.coerceAtLeast(1f))
            }
            return LongTermBetaProfile(map)
        }
    }
}
