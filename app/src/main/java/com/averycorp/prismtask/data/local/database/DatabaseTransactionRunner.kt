package com.averycorp.prismtask.data.local.database

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [androidx.room.withTransaction] so multi-DAO writes can
 * be wrapped in a single Room transaction without making repositories depend
 * on the concrete database in a way that breaks fake-DAO unit tests.
 *
 * Tests that don't use a real Room instance can subclass and override
 * [withTransaction] to simply invoke the block inline.
 */
@Singleton
open class DatabaseTransactionRunner @Inject constructor(private val database: PrismTaskDatabase) {
    open suspend fun <R> withTransaction(block: suspend () -> R): R =
        database.withTransaction(block)
}
