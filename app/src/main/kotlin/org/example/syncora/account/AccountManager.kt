package org.example.syncora.account

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.syncora.bitget.BitgetLiveCredentialsStore
import org.example.syncora.bitget.LiveTradingRepository
import org.example.syncora.bitget.PaperTradingRepository
import org.example.syncora.log.AppLog
import org.example.syncora.log.LogLevel
import org.example.syncora.rrl.RrlDataPipeline






enum class AccountMode {
    
    NONE,
    PAPER,
    LIVE,
}


sealed class AccountSelectionResult {
    object Success : AccountSelectionResult()

    
    object LiveCredentialsMissing : AccountSelectionResult()
}













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

    




    fun restoreActiveMode() {
        applyMode(_activeMode.value, persist = false)
    }

    
    fun selectPaper() {
        applyMode(AccountMode.PAPER, persist = true)
    }

    




    fun selectLive(): AccountSelectionResult {
        if (liveCredentialsStore.load() == null) {
            AppLog.account(LogLevel.WARNING, "Cannot switch to Live - no Bitget API key saved yet")
            return AccountSelectionResult.LiveCredentialsMissing
        }
        applyMode(AccountMode.LIVE, persist = true)
        return AccountSelectionResult.Success
    }

    
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
        
        rrlDataPipeline.setActive(mode != AccountMode.NONE)

        _activeMode.value = mode
        if (persist) persistMode(mode)

        val verb = if (persist) "switched to" else "restored to"
        when (mode) {
            AccountMode.PAPER -> AppLog.account(LogLevel.INFO, "Account $verb PAPER - trading and training active on the paper account")
            AccountMode.LIVE -> AppLog.account(LogLevel.INFO, "Account $verb LIVE - trading and training active on the Bitget live account")
            AccountMode.NONE -> if (persist) {
                AppLog.account(LogLevel.WARNING, "Account deselected - trading and training paused")
            }
        }
    }

    private fun persistMode(mode: AccountMode) {
        prefs.edit().putString(KEY_ACTIVE_MODE, mode.name).apply()
    }

    private fun loadPersistedMode(): AccountMode {
        val stored = prefs.getString(KEY_ACTIVE_MODE, null) ?: return AccountMode.NONE
        return runCatching { AccountMode.valueOf(stored) }.getOrDefault(AccountMode.NONE)
    }
}