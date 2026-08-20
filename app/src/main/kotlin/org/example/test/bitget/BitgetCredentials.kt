package org.example.test.bitget

/**
 * A Bitget **Demo API Key** triple (created while the account is switched to
 * Demo mode in the Bitget app: Personal Center -> API Key Management ->
 * Create Demo API Key). These keys can only touch the demo/paper-trading
 * balance - Bitget rejects them for live trading - but they are still
 * secrets and shouldn't be logged or committed anywhere.
 */
data class BitgetCredentials(
    val apiKey: String,
    val secretKey: String,
    val passphrase: String,
) {
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && secretKey.isNotBlank() && passphrase.isNotBlank()
}
