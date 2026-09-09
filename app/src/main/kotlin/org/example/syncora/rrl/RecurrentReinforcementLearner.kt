package org.example.syncora.rrl

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh






















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

    
    private val lastBackConnectionIndex: Int = reservoir.augmentedSize - 1

    
    private val pastPositions: ArrayDeque<Double> = ArrayDeque(List(config.nBack) { 0.0 })

    private var previousPosition: Double = 0.0
    private var previousZ: DoubleArray? = null
    private var previousDfDw: DoubleArray = DoubleArray(reservoir.augmentedSize)

    private var expectedReturn: Double = 0.0
    private var returnVariance: Double = 0.0

    
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

        
        val tau = config.emaDecay
        val newExpectedReturn = tau * expectedReturn + (1 - tau) * reward
        val rewardDeviation = reward - newExpectedReturn
        val newVariance = tau * returnVariance + (1 - tau) * rewardDeviation * rewardDeviation

        val sigma = sqrt(newVariance.coerceAtLeast(1e-12))
        
        
        
        val nonAnnualisedInformationRatio = (newExpectedReturn - config.benchmarkReturn) / sigma
        val informationRatio = config.annualisationFactor * nonAnnualisedInformationRatio
        val riskAppetite = when (config.riskAppetiteMode) {
            RrlAgentConfig.RiskAppetiteMode.FIXED -> config.fixedRiskAppetite
            RrlAgentConfig.RiskAppetiteMode.INFORMATION_RATIO -> nonAnnualisedInformationRatio / sigma
        }

        
        val utility = newExpectedReturn - 0.5 * riskAppetite * newVariance

        
        val dUtilityDReward = (1 - tau) * (1 - riskAppetite * rewardDeviation)
        val dRewardDPosition = -executionCost * sign(deltaF) - observation.fundingRate
        val dRewardDPreviousPosition = deltaPrice + executionCost * sign(deltaF)

        val tanhDerivative = 1 - position * position
        var dPositionDWeights = Matrix.scale(z, tanhDerivative)
        val previousZSnapshot = previousZ
        if (previousZSnapshot != null) {
            
            
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

        
        previousDfDw = dPositionDWeights
        previousZ = z
        previousPosition = position
        pastPositions.removeFirst()
        pastPositions.addLast(position)
        expectedReturn = newExpectedReturn
        returnVariance = newVariance

        
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
            gradientNorm = Matrix.norm(gradient),
            weightUpdateNorm = optimizer.lastUpdateNorm,
            weightNorm = Matrix.norm(optimizer.weights),
        )
    }

    








    fun snapshotState(): RrlLearnerState = RrlLearnerState(
        reservoirState = reservoir.snapshotState(),
        optimizerWeights = optimizer.weights.copyOf(),
        optimizerPrecision = optimizer.snapshotPrecision(),
        pastPositions = DoubleArray(pastPositions.size) { pastPositions[it] },
        previousPosition = previousPosition,
        previousZ = previousZ?.copyOf(),
        previousDfDw = previousDfDw.copyOf(),
        expectedReturn = expectedReturn,
        returnVariance = returnVariance,
    )

    






    fun restoreState(saved: RrlLearnerState): Boolean {
        if (saved.reservoirState.size != config.nHidden) return false
        if (saved.pastPositions.size != config.nBack) return false
        if (saved.optimizerWeights.size != reservoir.augmentedSize) return false
        if (saved.previousDfDw.size != reservoir.augmentedSize) return false
        if (saved.previousZ != null && saved.previousZ.size != reservoir.augmentedSize) return false

        reservoir.restoreState(saved.reservoirState)
        optimizer.restoreState(saved.optimizerWeights, saved.optimizerPrecision)
        for (i in pastPositions.indices) pastPositions[i] = saved.pastPositions[i]
        previousPosition = saved.previousPosition
        previousZ = saved.previousZ?.copyOf()
        previousDfDw = saved.previousDfDw.copyOf()
        expectedReturn = saved.expectedReturn
        returnVariance = saved.returnVariance
        return true
    }
}