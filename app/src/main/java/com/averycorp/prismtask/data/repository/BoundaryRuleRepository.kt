package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.BoundaryRuleDao
import com.averycorp.prismtask.data.local.entity.BoundaryRuleEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.model.BoundaryRule
import com.averycorp.prismtask.domain.model.BoundaryRuleType
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.BoundaryEnforcer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository wrapping [BoundaryRuleDao] for v1.4.0 V3.
 *
 * Translates between the persisted [BoundaryRuleEntity] (strings/CSVs) and
 * the runtime [BoundaryRule] (proper Java-time types) so the rest of the
 * app never has to know the storage format. Also handles first-run seeding
 * of the two built-in rules from [BoundaryEnforcer.BUILT_IN].
 */
@Singleton
class BoundaryRuleRepository
@Inject
constructor(private val dao: BoundaryRuleDao, private val syncTracker: SyncTracker) {
    fun observeRules(): Flow<List<BoundaryRule>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getRulesOnce(): List<BoundaryRule> =
        dao.getAll().map { it.toDomain() }

    suspend fun insert(rule: BoundaryRule): Long {
        val id = dao.insert(rule.toEntity(isBuiltIn = false))
        syncTracker.trackCreate(id, "boundary_rule")
        return id
    }

    suspend fun update(rule: BoundaryRule) {
        dao.update(rule.toEntity(isBuiltIn = false, id = rule.id))
        syncTracker.trackUpdate(rule.id, "boundary_rule")
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
        syncTracker.trackDelete(id, "boundary_rule")
    }

    /**
     * Seed the two built-in rules on first use. Idempotent — subsequent
     * calls are no-ops because the table already has rows.
     */
    suspend fun seedBuiltInIfEmpty() {
        if (dao.count() > 0) return
        for (rule in BoundaryEnforcer.BUILT_IN) {
            dao.insert(rule.toEntity(isBuiltIn = true))
        }
    }

    private fun BoundaryRuleEntity.toDomain(): BoundaryRule = BoundaryRule(
        id = id,
        name = name,
        ruleType = runCatching { BoundaryRuleType.valueOf(ruleType) }
            .getOrDefault(BoundaryRuleType.REMIND),
        category = LifeCategory.fromStorage(category).let {
            if (it == LifeCategory.UNCATEGORIZED) LifeCategory.WORK else it
        },
        startTime = BoundaryRule.parseTime(startTime),
        endTime = BoundaryRule.parseTime(endTime),
        activeDays = BoundaryRule.parseDays(activeDaysCsv),
        isEnabled = isEnabled
    )

    private fun BoundaryRule.toEntity(isBuiltIn: Boolean, id: Long = 0): BoundaryRuleEntity =
        BoundaryRuleEntity(
            id = id,
            name = name,
            ruleType = ruleType.name,
            category = category.name,
            startTime = BoundaryRule.formatTime(startTime),
            endTime = BoundaryRule.formatTime(endTime),
            activeDaysCsv = BoundaryRule.formatDays(activeDays),
            isEnabled = isEnabled,
            isBuiltIn = isBuiltIn,
            updatedAt = System.currentTimeMillis()
        )
}
