package org.example.syncora.account

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.syncora.bitget.BitgetLiveCredentialsStore
import org.example.syncora.bitget.LiveTradingRepository
import org.example.syncora.bitget.PaperTradingRepository
import org.example.syncora.rrl.RrlDataPipeline

/**
 * Which account is currently selected for trading, training, and fine-tuning.
 *
 * Exactly one of these is ever "active" at a time - see [AccountManager].
 */
enum class AccountMode {
    /** Nothing selected yet. No trading, training, or fine-tuning happens against either account. */
    NONE,
    PAPER,
    LIVE,
}

/** Result of attempting to select an account mode. */
sealed class AccountSelectionResult {
    object Success : AccountSelectionResult()

    /** Live mode was requested but no Bitget API credentials are saved yet. */
    object LiveCredentialsMissing : AccountSelectionResult()
}

/**
 * Single source of truth for which account - Paper or Live - is currently active across the whole app.
 *
 * Trading, the RRL training loop, and fine-tuning (checkpoint restore/update via [RrlDataPipeline]) all
 * read from the *same* [activeMode] flag, and the individual repositories/pipeline enforce it themselves
 * (see `setActive` on [LiveTradingRepository], [PaperTradingRepository], and [RrlDataPipeline]) so that
 * even a direct or accidental call into the inactive account's trading methods is rejected.
 *
 * Switching modes never wipes data. The account being deselected is simply paused (its background jobs
 * stopped and its trade-execution methods disabled) while its persisted balance/positions/history stay
 * on disk untouched, so switching back resumes exactly where it left off.
 */
class AccountManager(
    context: Context,
    private val liveCredentialsStore: BitgetLiveCredentialsStore,
    private val liveTradingRepository: LiveTradingRepository,
    private val paperTradingRepository: PaperTradingRepository,
    private val rrlDataPipeline: RrlDataPipeline,
) {
    private companion object {
        const val PREFS_NAME = "syncora_account_manager"
        const val KEY_ACTIVE_MODE = "active_mode"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _activeMode = MutableStateFlow(loadPersistedMode())
    val activeMode: StateFlow<AccountMode> = _activeMode.asStateFlow()

    /**
     * Applies the persisted mode (or NONE on first launch) to the repositories/pipeline. Call this once,
     * from [org.example.syncora.SyncoraApplication.ensureMarketDataStarted], instead of starting the live
     * or paper repositories directly - this is what guarantees only one account is ever live at process start.
     */
    fun restoreActiveMode() {
        applyMode(_activeMode.value, persist = false)
    }

    /** Selects Paper as the active account. Live is paused (frozen, not logged out or wiped). */
    fun selectPaper() {
        applyMode(AccountMode.PAPER, persist = true)
    }

    /**
     * Selects Live as the active account. Paper is paused (frozen, not wiped).
     * Fails with [AccountSelectionResult.LiveCredentialsMissing] if no API key has been saved -
     * live mode can never be silently activated without credentials.
     */
    fun selectLive(): AccountSelectionResult {
        if (liveCredentialsStore.load() == null) {
            return AccountSelectionResult.LiveCredentialsMissing
        }
        applyMode(AccountMode.LIVE, persist = true)
        return AccountSelectionResult.Success
    }

    /** Deselects both accounts. Trading, training, and fine-tuning all pause until a mode is chosen again. */
    fun deselect() {
        applyMode(AccountMode.NONE, persist = true)
    }

    fun isActive(mode: AccountMode): Boolean = _activeMode.value == mode

    private fun applyMode(mode: AccountMode, persist: Boolean) {
        when (mode) {
            AccountMode.PAPER -> {
                liveTradingRepository.setActive(false)
                paperTradingRepository.setActive(true)
                paperTradingRepository.start()
            }
            AccountMode.LIVE -> {
                paperTradingRepository.setActive(false)
                liveTradingRepository.setActive(true)
                liveTradingRepository.start()
            }
            AccountMode.NONE -> {
                liveTradingRepository.setActive(false)
                paperTradingRepository.setActive(false)
            }
        }
        // Training and fine-tuning always follow whichever account is active, and pause with it.
        rrlDataPipeline.setActive(mode != AccountMode.NONE)

        _activeMode.value = mode
        if (persist) persistMode(mode)
    }

    private fun persistMode(mode: AccountMode) {
        prefs.edit().putString(KEY_ACTIVE_MODE, mode.name).apply()
    }

    private fun loadPersistedMode(): AccountMode {
        val stored = prefs.getString(KEY_ACTIVE_MODE, null) ?: return AccountMode.NONE
        return runCatching { AccountMode.valueOf(stored) }.getOrDefault(AccountMode.NONE)
    }
}
