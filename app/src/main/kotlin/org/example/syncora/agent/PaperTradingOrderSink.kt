package org.example.syncora.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PaperTradingRepository
import org.example.syncora.bitget.PositionSide

/**
 * Adapts [PaperTradingRepository]'s suspend `openPosition`/`closePosition`
 * calls to the plain, non-suspending [PaperOrderSink] surface
 * [PositionOrderEmitter] and [HardenedAgentLiveSession] expect - see both
 * classes' docs for why the order path stays out of coroutine territory.
 * Each call is fired on [scope] and not awaited: the agent's own per-bar
 * loop is not a suspend function (mirrors [AgentLiveSession.processLiveBar]'s
 * own doc on this exact point), so there is nothing for a caller here to
 * usefully wait on. Failures surface the same way every other
 * [PaperTradingRepository] caller already handles them - via
 * [PaperTradingRepository.lastError] - rather than being swallowed
 * silently or thrown back into the agent's live loop.
 */
class PaperTradingOrderSink(
    private val repository: PaperTradingRepository,
    private val scope: CoroutineScope,
) : PaperOrderSink {

    override fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int) {
        scope.launch { repository.openPosition(side, sizeInBaseCoin, leverage) }
    }

    override fun closePosition(position: PaperPosition) {
        scope.launch { repository.closePosition(position) }
    }
}
