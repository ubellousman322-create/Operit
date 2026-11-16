package com.ai.assistance.operit.data.db

import io.objectbox.BoxStore
import java.util.concurrent.ConcurrentHashMap

object ObjectBoxManager {
    private val stores = ConcurrentHashMap<String, BoxStore>()
    private val storeLock = Any()

    fun close(profileId: String) {
        synchronized(storeLock) {
            val store = stores.remove(profileId) ?: return
            store.close()
        }
    }

    fun closeAll() {
        synchronized(storeLock) {
            stores.values.forEach { it.close() }
            stores.clear()
        }
    }
} 
