package org.example.syncora.resilience

import org.example.syncora.agent.ExperienceLogStore
import org.example.syncora.agent.RewardStatus
import org.junit.Assert.assertEquals

/**
 * Thin wrappers around [ExperienceLogStore]'s read-only aggregate queries
 * (`countAll`/`countWhereStatusNot`/`countWhereFundingComponentNull`/
 * `countPendingOlderThan`, added alongside this harness), phrased as the
 * pass/fail assertions [PipelineResilienceTest] needs at each checkpoint.
 * Mirrors the design doc's `ExperienceLogDao`-based sketch, adapted to this
 * codebase's actual [ExperienceLogStore] (there's no separate Room DAO
 * here - one SQLite-backed store owns both writes and reads).
 */
object ExperienceLogAssertions {

    fun assertRowCount(store: ExperienceLogStore, expected: Int) {
        assertEquals("Experience log row count mismatch — possible data loss", expected, store.countAll())
    }

    fun assertAllRowsHaveStatus(store: ExperienceLogStore, status: RewardStatus) {
        assertEquals("Rows found with unexpected reward_status", 0, store.countWhereStatusNot(status))
    }

    fun assertFundingComponentBackfilled(store: ExperienceLogStore) {
        assertEquals("Funding settlement did not back-fill expected rows", 0, store.countWhereFundingComponentNull())
    }

    fun assertNoOrphanedPendingRows(store: ExperienceLogStore, nowMs: Long, maxAge: Long) {
        val orphaned = store.countPendingOlderThan(nowMs - maxAge)
        assertEquals(
            "Found pending rows older than resolution window — back-fill likely broken by kill",
            0,
            orphaned,
        )
    }

    fun assertAllRowsResolved(store: ExperienceLogStore, olderThan: Long) {
        assertEquals(
            "Rows remain unresolved past expected resolution point",
            0,
            store.countWhereStatusNot(RewardStatus.RESOLVED, before = olderThan),
        )
    }
}
