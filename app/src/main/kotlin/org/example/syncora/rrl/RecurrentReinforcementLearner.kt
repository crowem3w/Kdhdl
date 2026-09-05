package org.example.syncora.rrl

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Target model of Borrageiro, Firoozye & Barucca, "The Recurrent
 * Reinforcement Learning Crypto Agent" (section III-B2): a direct, recurrent
 * reinforcement learner that consumes the augmented state z_t produced by an
 * [EchoStateReservoir] and targets a risk position f_t in [-1, 1] directly,
 * by maximising a quadratic, risk-adjusted utility of reward and risk
 * (eq. 6) via an online extended-Kalman-filter weight update ([RrlWeightOptimizer],
 * "Algorithm 1").
 *
 * One call to [step] corresponds to one sampling interval and:
 *  1. builds yhat_t from the buffer of past desired positions (eq. 11);
 *  2. advances the reservoir to get z_t = [u_t, x_t, yhat_t] (eq. 5);
 *  3. computes the desired position f_t = tanh(w_t^T z_t) (eq. 10);
 *  4. decomposes the net reward r_t into price, execution and funding
 *     components (eq. 8, eq. 9);
 *  5. updates the online mean/variance of returns and the quadratic utility
 *     (eq. 6, eq. 7);
 *  6. computes the utility gradient w.r.t. the readout weights (eq. 12 and
 *     the df_t/dw_t^out identity that follows it) and applies one sequential
 *     extended Kalman filter update.
 */
class RecurrentReinforcementLearner(private val config: RrlAgentConfig) {

    private val reservoir = EchoStateReservoir(
        nInput = config.nInput,
        nHidden = config.nHidden,
        nBack = config.nBack,
        sparsity = config.sparsity,
        spectralRadius = config.spectralRadius,
        signFlipProbability = config.signFlipProbability,
        seed = config.seed,
    )

    private val optimizer = RrlWeightOptimizer(
        dimension = reservoir.augmentedSize,
        ridgePenalty = config.ridgePenalty,
        decayFactor = config.kalmanDecay,
    )

    /** Index of f_{t-1} within z_t = [u_t, x_t, yhat_t]; yhat_t's last element is f_{t-1} (eq. 11). */
    private val lastBackConnectionIndex: Int = reservoir.augmentedSize - 1

    /** Circular buffer of the last nBack desired positions, oldest first: [f_{t-nBack}, ..., f_{t-1}]. */
    private val pastPositions: ArrayDeque<Double> = ArrayDeque(List(config.nBack) { 0.0 })

    private var previousPosition: Double = 0.0
    private var previousZ: DoubleArray? = null
    private var previousDfDw: DoubleArray = DoubleArray(reservoir.augmentedSize)

    private var expectedReturn: Double = 0.0
    private var returnVariance: Double = 0.0

    /** Resets all learned state: reservoir, weights and running statistics. */
    fun reset() {
        reservoir.reset()
        optimizer.reset()
        for (i in pastPositions.indices) pastPositions[i] = 0.0
        previousPosition = 0.0
        previousZ = null
        previousDfDw = DoubleArray(reservoir.augmentedSize)
        expectedReturn = 0.0
        returnVariance = 0.0
    }

    /**
     * Advances the agent by one observation.
     *
     * @param observation this step's external features, prices and funding rate.
     * @param deltaPrice Delta p_t: the change in reference (mid) price since the previous observation (eq. 8).
     * @param executionCost the total price-taker execution cost rate for this observation: half the
     *   bid/ask spread (eq. 9) plus the prevailing exchange taker fee, both expressed as a fraction
     *   of price so that `executionCost * |Delta f_t|` is the cost of a full-notional round trip.
     */
    fun step(observation: MarketObservation, deltaPrice: Double, executionCost: Double): RrlStepResult {
        val yHat = DoubleArray(config.nBack) { i -> pastPositions[i] }
        val z = reservoir.step(observation.features, yHat)

        val rawWeights = optimizer.weights
        val activation = Matrix.dot(rawWeights, z)
        val position = tanh(activation)

        val deltaF = position - previousPosition
        val executionCostTerm = -executionCost * abs(deltaF)
        val priceReturnTerm = deltaPrice * previousPosition
        val fundingCarryTerm = -observation.fundingRate * position

        val reward = priceReturnTerm + executionCostTerm + fundingCarryTerm

        // eq. 7: online exponentially-weighted mean/variance of net returns.
        val tau = config.emaDecay
        val newExpectedReturn = tau * expectedReturn + (1 - tau) * reward
        val rewardDeviation = reward - newExpectedReturn
        val newVariance = tau * returnVariance + (1 - tau) * rewardDeviation * rewardDeviation

        val sigma = sqrt(newVariance.coerceAtLeast(1e-12))
        // Reported ir_t is annualised (as defined in section III-B2); lambda, however, is derived
        // by substituting the *non-annualised* ratio (mu_t - b_t) / sigma_t into the quadratic
        // utility and differentiating against risk, giving lambda = (mu_t - b_t) / sigma_t^2.
        val nonAnnualisedInformationRatio = (newExpectedReturn - config.benchmarkReturn) / sigma
        val informationRatio = config.annualisationFactor * nonAnnualisedInformationRatio
        val riskAppetite = when (config.riskAppetiteMode) {
            RrlAgentConfig.RiskAppetiteMode.FIXED -> config.fixedRiskAppetite
            RrlAgentConfig.RiskAppetiteMode.INFORMATION_RATIO -> nonAnnualisedInformationRatio / sigma
        }

        // eq. 6: quadratic, risk-adjusted utility.
        val utility = newExpectedReturn - 0.5 * riskAppetite * newVariance

        // --- eq. 12 and the df_t/dw_t^out identity: gradient of the utility w.r.t. w^out. ---
        val dUtilityDReward = (1 - tau) * (1 - riskAppetite * rewardDeviation)
        val dRewardDPosition = -executionCost * sign(deltaF) - observation.fundingRate
        val dRewardDPreviousPosition = deltaPrice + executionCost * sign(deltaF)

        val tanhDerivative = 1 - position * position
        var dPositionDWeights = Matrix.scale(z, tanhDerivative)
        val previousZSnapshot = previousZ
        if (previousZSnapshot != null) {
            // Recursive term: w_{t,n} * (1 - f_t^2) * z_{t-1} * (1 - f_{t-1}^2), where index n
            // is the position of f_{t-1} inside z_t (last element of the yhat_t back-connections).
            val weightAtBackConnection = rawWeights[lastBackConnectionIndex]
            val previousTanhDerivative = 1 - previousPosition * previousPosition
            val recursiveScale = weightAtBackConnection * tanhDerivative * previousTanhDerivative
            val recursiveTerm = Matrix.scale(previousZSnapshot, recursiveScale)
            dPositionDWeights = Matrix.add(dPositionDWeights, recursiveTerm)
        }

        val gradient = Matrix.add(
            Matrix.scale(dPositionDWeights, dUtilityDReward * dRewardDPosition),
            Matrix.scale(previousDfDw, dUtilityDReward * dRewardDPreviousPosition),
        )

        optimizer.update(gradient)

        // Roll state forward for the next step.
        previousDfDw = dPositionDWeights
        previousZ = z
        previousPosition = position
        pastPositions.removeFirst()
        pastPositions.addLast(position)
        expectedReturn = newExpectedReturn
        returnVariance = newVariance

        // Final bullet of section III-C: only trade freely while expected net return is non-negative.
        val gatedPosition = if (config.gateOnExpectedReturn && newExpectedReturn < 0.0) 0.0 else position

        return RrlStepResult(
            timestampMs = observation.timestampMs,
            position = gatedPosition,
            rawPosition = position,
            priceReturn = priceReturnTerm,
            executionCost = executionCostTerm,
            fundingCarry = fundingCarryTerm,
            reward = reward,
            expectedReturn = newExpectedReturn,
            variance = newVariance,
            utility = utility,
            informationRatio = informationRatio,
            riskAppetite = riskAppetite,
        )
    }
}
