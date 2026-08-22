package org.example.test.agent.sim

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * `kotlin.random.Random` only offers uniform sampling. Box-Muller is the
 * standard way to get standard-normal draws from it without pulling in a
 * stats library for one function - shared here since every generator in
 * this package ([RegimeSwitchingPriceProcess], [SyntheticMarketDataGenerator])
 * needs Gaussian shocks somewhere.
 */
internal fun Random.nextGaussian(): Double {
    var u1: Double
    do {
        u1 = nextDouble()
    } while (u1 <= 1e-12)
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
}
