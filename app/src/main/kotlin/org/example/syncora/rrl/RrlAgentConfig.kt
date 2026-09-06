package org.example.syncora.rrl






data class RrlAgentConfig(
    
    val nInput: Int = RrlFeatureExtractor.FEATURE_COUNT,
    
    val nHidden: Int = 100,
    
    val nBack: Int = 10,
    
    val sparsity: Double = 0.75,
    
    val spectralRadius: Double = 0.9,
    
    val signFlipProbability: Double = 0.5,
    
    val ridgePenalty: Double = 1.0,
    
    val kalmanDecay: Double = 0.999,
    
    val emaDecay: Double = 0.999,
    





    val riskAppetiteMode: RiskAppetiteMode = RiskAppetiteMode.FIXED,
    
    val fixedRiskAppetite: Double = 0.00001,
    
    val annualisationFactor: Double = kotlin.math.sqrt(252.0),
    
    val benchmarkReturn: Double = 0.0,
    





    val exchangeFeeRate: Double = 0.0005,
    




    val gateOnExpectedReturn: Boolean = true,
    
    val seed: Long = 42L,
) {
    enum class RiskAppetiteMode { FIXED, INFORMATION_RATIO }

    











    fun fingerprint(): String {
        val fields = listOf(
            nInput, nHidden, nBack, sparsity, spectralRadius, signFlipProbability,
            ridgePenalty, kalmanDecay, emaDecay, riskAppetiteMode, fixedRiskAppetite,
            annualisationFactor, benchmarkReturn, gateOnExpectedReturn, seed,
        ).joinToString("|")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(fields.toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }
}