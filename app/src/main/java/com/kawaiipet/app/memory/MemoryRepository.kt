package com.kawaiipet.app.memory

import com.kawaiipet.app.memory.db.FactDao
import com.kawaiipet.app.memory.db.FactEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val factDao: FactDao
) {
    val allFacts: Flow<List<FactEntity>> = factDao.getAllFacts()

    suspend fun insertFact(fact: FactEntity): Long = factDao.insert(fact)

    suspend fun deleteFact(fact: FactEntity) = factDao.delete(fact)

    suspend fun purgeContaminatedFacts() = factDao.deleteContaminatedFacts()

    suspend fun touchFact(factId: Long) =
        factDao.updateLastAccessed(factId, System.currentTimeMillis())

    suspend fun getRecentFacts(limit: Int = 20): List<FactEntity> =
        factDao.getRecentFacts(limit)

    suspend fun findFactsByKeywords(keywords: List<String>): List<FactEntity> {
        if (keywords.isEmpty()) return getRecentFacts(5)

        return coroutineScope {
            keywords
                .map { keyword -> async { factDao.findByKeyword(keyword) } }
                .awaitAll()
                .flatten()
                .distinctBy { it.id }
                .sortedByDescending { it.importanceScore }
                .take(10)
        }
    }
}
