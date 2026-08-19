package org.example.test.bitget

interface KlineCacheStore {

    suspend fun load(): List<Kline>?

    suspend fun save(candles: List<Kline>)
}

object NoopKlineCacheStore : KlineCacheStore {
    override suspend fun load(): List<Kline>? = null
    override suspend fun save(candles: List<Kline>) = Unit
}
