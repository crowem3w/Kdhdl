package org.example.syncora.rrl














data class MarketObservation(
    val timestampMs: Long,
    val bid: Double,
    val ask: Double,
    val features: DoubleArray,
    val fundingRate: Double,
)






data class RrlStepResult(
    val timestampMs: Long,
    
    val position: Double,
    
    val rawPosition: Double,
    
    val priceReturn: Double,
    
    val executionCost: Double,
    
    val fundingCarry: Double,
    
    val reward: Double,
    
    val expectedReturn: Double,
    
    val variance: Double,
    
    val utility: Double,
    
    val informationRatio: Double,
    
    val riskAppetite: Double,
)










data class RrlLearnerState(
    
    val reservoirState: DoubleArray,
    
    val optimizerWeights: DoubleArray,
    
    val optimizerPrecision: Array<DoubleArray>,
    
    val pastPositions: DoubleArray,
    
    val previousPosition: Double,
    
    val previousZ: DoubleArray?,
    
    val previousDfDw: DoubleArray,
    
    val expectedReturn: Double,
    
    val returnVariance: Double,
)