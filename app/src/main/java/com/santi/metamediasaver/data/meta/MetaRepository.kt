package com.santi.metamediasaver.data.meta

import com.google.firebase.functions.FirebaseFunctions
import com.santi.metamediasaver.data.model.ConnectedAccount
import com.santi.metamediasaver.data.model.MediaItem
import com.santi.metamediasaver.data.model.MediaType
import com.santi.metamediasaver.data.model.PagedMedia
import com.santi.metamediasaver.data.model.SourceType
import kotlinx.coroutines.tasks.await

interface MetaRepository {
    suspend fun startConnection(): String
    suspend fun finishConnection(code: String, state: String): List<ConnectedAccount>
    suspend fun listConnectedAccounts(): List<ConnectedAccount>
    suspend fun listMedia(accountId: String, cursor: String? = null): PagedMedia
    suspend fun refreshMediaUrl(mediaId: String): String?
    suspend fun disconnectMeta(accountId: String)
}

class FirebaseMetaRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) : MetaRepository {
    override suspend fun startConnection(): String {
        val data = call("startMetaConnection")
        return data.string("authorizationUrl")
            ?: error("Backend did not return an authorization URL.")
    }

    override suspend fun finishConnection(code: String, state: String): List<ConnectedAccount> {
        val data = call(
            "finishMetaConnection",
            mapOf(
                "code" to code,
                "state" to state
            )
        )
        return data.list("accounts").mapNotNull { it.asMapOrNull()?.toConnectedAccount() }
    }

    override suspend fun listConnectedAccounts(): List<ConnectedAccount> {
        val data = call("listConnectedAccounts")
        return data.list("accounts").mapNotNull { it.asMapOrNull()?.toConnectedAccount() }
    }

    override suspend fun listMedia(accountId: String, cursor: String?): PagedMedia {
        val data = call(
            "listMedia",
            buildMap {
                put("accountId", accountId)
                cursor?.let { put("cursor", it) }
            }
        )

        return PagedMedia(
            items = data.list("items").mapNotNull { it.asMapOrNull()?.toMediaItem() },
            nextCursor = data.string("nextCursor")
        )
    }

    override suspend fun refreshMediaUrl(mediaId: String): String? {
        val data = call("refreshMediaUrl", mapOf("mediaId" to mediaId))
        return data.string("mediaUrl")
    }

    override suspend fun disconnectMeta(accountId: String) {
        call("disconnectMeta", mapOf("accountId" to accountId))
    }

    private suspend fun call(
        name: String,
        payload: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val result = functions.getHttpsCallable(name).call(payload).await().data
        return result.asMapOrNull().orEmpty()
    }

    private fun Map<String, Any?>.toConnectedAccount(): ConnectedAccount = ConnectedAccount(
        id = string("id").orEmpty(),
        displayName = string("displayName").orEmpty(),
        username = string("username"),
        sourceType = SourceType.fromWire(string("sourceType")),
        avatarUrl = string("avatarUrl")
    )

    private fun Map<String, Any?>.toMediaItem(): MediaItem = MediaItem(
        id = string("id").orEmpty(),
        accountId = string("accountId").orEmpty(),
        caption = string("caption"),
        mediaType = MediaType.fromWire(string("mediaType")),
        mediaUrl = string("mediaUrl"),
        thumbnailUrl = string("thumbnailUrl"),
        permalink = string("permalink"),
        sourceType = SourceType.fromWire(string("sourceType")),
        timestamp = string("timestamp"),
        downloadable = boolean("downloadable")
            ?: !string("mediaUrl").isNullOrBlank()
    )

    private fun Map<String, Any?>.string(key: String): String? =
        this[key]?.toString()?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.boolean(key: String): Boolean? =
        when (val value = this[key]) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }

    private fun Map<String, Any?>.list(key: String): List<Any?> =
        this[key] as? List<Any?> ?: emptyList()

    private fun Any?.asMapOrNull(): Map<String, Any?>? {
        val map = this as? Map<*, *> ?: return null
        return map.entries.associate { (key, value) -> key.toString() to value }
    }
}
