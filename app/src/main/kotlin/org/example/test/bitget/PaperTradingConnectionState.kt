package org.example.test.bitget

/**
 * Generic "connection status" wrapper for a Bitget-API-backed trading
 * repository. Despite the name, this is not paper-trading-specific - it's
 * used only by [LiveTradingRepository]/[LiveTradePanel] now that Paper
 * Trading is a fully local simulation with no connection to represent
 * (see [PaperTradingRepository], which no longer uses this).
 */
enum class PaperTradingConnectionState {
    NOT_CONFIGURED,
    LOADING,
    LIVE,
    ERROR,
}
