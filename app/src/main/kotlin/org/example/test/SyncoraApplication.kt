package org.example.test

import android.app.Application
import org.example.test.bitget.BitgetCredentialsStore
import org.example.test.bitget.DepthPipeline
import org.example.test.bitget.FileKlineCacheStore
import org.example.test.bitget.PaperTradingRepository
import org.example.test.bitget.Timeframe
import org.example.test.bitget.TradingChartPipeline

/**
 * Holds the market-data pipelines at application scope instead of activity scope.
 *
 * SplashActivity needs to know when the first candles have actually arrived (and keep
 * retrying quietly if they haven't, e.g. no internet) *before* it hands off to Onboarding
 * or MainActivity. If each activity created its own [TradingChartPipeline], MainActivity's
 * onStart() would call start() again right after Splash finished priming it, wiping the
 * freshly-loaded candles and dropping the user back into a loading skeleton — defeating the
 * whole point of waiting on the splash screen. Sharing one instance here, gated by
 * [ensureMarketDataStarted], lets Splash prime the connection and MainActivity simply pick
 * up the already-live stream.
 */
class SyncoraApplication : Application() {

    val pipeline: TradingChartPipeline by lazy {
        TradingChartPipeline(
            instId = "BTCUSDT",
            instType = "USDT-FUTURES",
            initialTimeframe = Timeframe.DEFAULT,
            bufferCapacity = 1000,
            cacheStore = FileKlineCacheStore(
                applicationContext,
                cacheKey = "BTCUSDT_USDT-FUTURES_${Timeframe.DEFAULT.wsChannel}",
            ),
        )
    }

    val depthPipeline: DepthPipeline by lazy {
        DepthPipeline(instId = "BTCUSDT", instType = "USDT-FUTURES")
    }

    val credentialsStore: BitgetCredentialsStore by lazy {
        BitgetCredentialsStore(applicationContext)
    }

    val paperTradingRepository: PaperTradingRepository by lazy {
        PaperTradingRepository(credentialsStore = credentialsStore, symbol = "BTCUSDT")
    }

    private var marketDataStarted = false

    /** Idempotent: safe to call from both SplashActivity and MainActivity.onStart(). */
    fun ensureMarketDataStarted() {
        if (marketDataStarted) return
        marketDataStarted = true
        pipeline.start()
        depthPipeline.start()
    }

    /** Call when the chart is no longer visible to anything (MainActivity.onStop()). */
    fun stopMarketData() {
        marketDataStarted = false
        pipeline.stop()
        depthPipeline.stop()
    }
}
