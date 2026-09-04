package org.example.syncora

import android.app.Application
import org.example.syncora.bitget.BitgetFeeRateClient
import org.example.syncora.bitget.BitgetFundingRateClient
import org.example.syncora.bitget.BitgetLiveCredentialsStore
import org.example.syncora.bitget.BitgetKlineRestClient
import org.example.syncora.bitget.BitgetTradeSocket
import org.example.syncora.bitget.DeepHistoryBackfillJob
import org.example.syncora.bitget.DepthPipeline
import org.example.syncora.bitget.KlineArchiveStore
import org.example.syncora.bitget.LiveTradingRepository
import org.example.syncora.bitget.LocalPaperTradingStore
import org.example.syncora.bitget.ObjectBoxKlineCacheStore
import org.example.syncora.bitget.ObjectBoxStore
import org.example.syncora.bitget.PaperTradingRepository
import org.example.syncora.bitget.RiskSettingsStore
import org.example.syncora.bitget.StopLossGuard
import org.example.syncora.bitget.Timeframe
import org.example.syncora.bitget.TradingChartPipeline















class SyncoraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        
        
        
        ObjectBoxStore.init(this)
    }

    val pipeline: TradingChartPipeline by lazy {
        TradingChartPipeline(
            instId = "BTCUSDT",
            instType = "USDT-FUTURES",
            initialTimeframe = Timeframe.DEFAULT,
            
            
            
            
            
            bufferCapacity = 4_320,
            cacheStore = ObjectBoxKlineCacheStore(
                boxStore = ObjectBoxStore.requireStore(),
                cacheKey = "BTCUSDT_USDT-FUTURES_${Timeframe.DEFAULT.wsChannel}",
            ),
        )
    }

    
    
    
    
    
    
    val deepHistoryBackfillJob: DeepHistoryBackfillJob by lazy {
        DeepHistoryBackfillJob(
            restClient = BitgetKlineRestClient(),
            archiveStore = KlineArchiveStore(ObjectBoxStore.requireStore()),
            instId = "BTCUSDT",
            productType = "usdt-futures",
            granularity = Timeframe.ONE_MINUTE.restParam,
        )
    }

    val depthPipeline: DepthPipeline by lazy {
        DepthPipeline(instId = "BTCUSDT", instType = "USDT-FUTURES")
    }

    
    
    
    val tradeSocket: BitgetTradeSocket by lazy {
        BitgetTradeSocket(instId = "BTCUSDT", instType = "USDT-FUTURES")
    }

    val paperTradingStore: LocalPaperTradingStore by lazy {
        LocalPaperTradingStore(applicationContext)
    }

    val liveCredentialsStore: BitgetLiveCredentialsStore by lazy {
        BitgetLiveCredentialsStore(applicationContext)
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
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

    val riskSettingsStore: RiskSettingsStore by lazy {
        RiskSettingsStore(applicationContext)
    }

    
    
    
    
    
    
    val stopLossGuard: StopLossGuard by lazy {
        StopLossGuard(credentialsStore = liveCredentialsStore, riskSettingsStore = riskSettingsStore)
    }

    private var marketDataStarted = false

    






    fun ensureMarketDataStarted() {
        if (marketDataStarted) return
        marketDataStarted = true
        pipeline.start()
        depthPipeline.start()
        tradeSocket.connect()
        liveTradingRepository.start()
        stopLossGuard.start(liveTradingRepository.positions)
    }

    
    fun stopMarketData() {
        marketDataStarted = false
        pipeline.stop()
        depthPipeline.stop()
        tradeSocket.disconnect()
        liveTradingRepository.stop()
        stopLossGuard.stop()
    }
}