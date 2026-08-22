package org.example.test

import android.app.Application
import org.example.test.agent.AgentDataIngestionService
import org.example.test.agent.FileAgentFeatureCacheStore
import org.example.test.bitget.BitgetFeeRateClient
import org.example.test.bitget.BitgetFundingRateClient
import org.example.test.bitget.BitgetLiveCredentialsStore
import org.example.test.bitget.BitgetTradeSocket
import org.example.test.bitget.DepthPipeline
import org.example.test.bitget.FileKlineCacheStore
import org.example.test.bitget.LiveTradingRepository
import org.example.test.bitget.LocalPaperTradingStore
import org.example.test.bitget.PaperTradingRepository
import org.example.test.bitget.Timeframe
import org.example.test.bitget.TradingChartPipeline

/**
 * Holds the market-data pipelines at application scope instead of activity scope.
 *
 * Keeping the pipelines here instead of inside MainActivity means they survive
 * configuration changes and brief activity recreation without dropping the live stream.
 * [ensureMarketDataStarted] is idempotent so it's safe to call from onStart() regardless
 * of whether the pipelines are already running.
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

    // Public trade-print stream, used only by the paper trading engine to
    // estimate queue position for resting limit orders (see
    // QueuePositionTracker) - public market data, no trading permissions.
    val tradeSocket: BitgetTradeSocket by lazy {
        BitgetTradeSocket(instId = "BTCUSDT", instType = "USDT-FUTURES")
    }

    val paperTradingStore: LocalPaperTradingStore by lazy {
        LocalPaperTradingStore(applicationContext)
    }

    val liveCredentialsStore: BitgetLiveCredentialsStore by lazy {
        BitgetLiveCredentialsStore(applicationContext)
    }

    // Fully local paper trading: no trading permissions, no exchange
    // matching engine, no Demo API Key - every position, fill, and balance
    // is simulated entirely on-device. It reads a few things off the
    // network, all public/read-only: the live mark price and L2 order book
    // off the same market-data pipelines the chart itself uses (so
    // simulated fills are priced by walking real depth - see
    // OrderBookWalker - instead of a flat assumed slippage), the public
    // trade-print stream (used only to estimate queue position for resting
    // limit orders - see QueuePositionTracker), and Bitget's real
    // maker/taker fee rates (see BitgetFeeRateClient) so simulated fills
    // are charged what a live order actually costs instead of a guessed
    // flat fee. If a Live Trading API key happens to be saved, its actual
    // account-tier rate is used for the fee refresh instead of the public
    // standard tier - still read-only, still optional. It also pulls
    // Bitget's real funding rate (see BitgetFundingRateClient) and accrues
    // it against open positions on the exchange's actual settlement
    // schedule (design doc §7) instead of ignoring it.
    val paperTradingRepository: PaperTradingRepository by lazy {
        PaperTradingRepository(
            store = paperTradingStore,
            symbol = "BTCUSDT",
            markPriceProvider = { pipeline.klines.value.lastOrNull()?.close },
            feeRateClient = BitgetFeeRateClient(),
            feeRateCredentialsProvider = { liveCredentialsStore.load() },
            fundingRateClient = BitgetFundingRateClient(),
            depthSnapshotProvider = { depthPipeline.depth.value },
            tradeFlow = tradeSocket.trades,
        )
    }

    val liveTradingRepository: LiveTradingRepository by lazy {
        LiveTradingRepository(credentialsStore = liveCredentialsStore, symbol = "BTCUSDT")
    }

    // Design doc §7.4's "Data ingestion service" - feeds the same kline/
    // depth/trade streams the chart already maintains (plus its own new
    // ticker socket for mark/index price, funding rate, and open interest)
    // into a shared feature store for the future agent/regime-detector
    // services to consume. No trading permissions, no new order-book or
    // kline sockets - reuses `pipeline`/`depthPipeline`/`tradeSocket`
    // above rather than opening duplicate connections for the same public
    // data.
    val agentDataIngestionService: AgentDataIngestionService by lazy {
        AgentDataIngestionService(
            instId = "BTCUSDT",
            instType = "USDT-FUTURES",
            klines = pipeline.klines,
            depth = depthPipeline.depth,
            trades = tradeSocket.trades,
            cacheStore = FileAgentFeatureCacheStore(applicationContext, cacheKey = "BTCUSDT_USDT-FUTURES"),
        )
    }

    private var marketDataStarted = false

    /** Idempotent: safe to call repeatedly from MainActivity.onStart(). */
    fun ensureMarketDataStarted() {
        if (marketDataStarted) return
        marketDataStarted = true
        pipeline.start()
        depthPipeline.start()
        tradeSocket.connect()
        agentDataIngestionService.start()
    }

    /** Call when the chart is no longer visible to anything (MainActivity.onStop()). */
    fun stopMarketData() {
        marketDataStarted = false
        pipeline.stop()
        depthPipeline.stop()
        tradeSocket.disconnect()
        agentDataIngestionService.stop()
    }
}
