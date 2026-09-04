package org.example.syncora.bitget











data class BitgetCredentials(
    val apiKey: String,
    val secretKey: String,
    val passphrase: String,
) {
    
    
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && secretKey.isNotBlank()
}