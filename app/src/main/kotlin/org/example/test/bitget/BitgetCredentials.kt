package org.example.test.bitget

/**
 * A Bitget API Key triple (API key, secret, passphrase), used for **Live
 * Trading** against Bitget's real matching engine ([BitgetLiveCredentialsStore]
 * + [LiveTradingRepository]).
 *
 * Paper Trading no longer uses this at all - it's a fully local simulation
 * (see [PaperTradingRepository] / [LocalPaperTradingStore]) with no API key,
 * Demo or otherwise. These are secrets and shouldn't be logged or committed
 * anywhere.
 */
data class BitgetCredentials(
    val apiKey: String,
    val secretKey: String,
    val passphrase: String,
) {
    // Passphrase is optional: most Bitget API Keys require one, but not all
    // exchanges/keys do, so we don't hard-block saving without it.
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && secretKey.isNotBlank()
}
