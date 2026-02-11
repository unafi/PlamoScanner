package com.example.plamoscanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Notion APIとの通信ロジックを共通化したリポジトリクラス。
 * データの取得・作成・更新をカプセル化し、DRY原則を適用します。
 */
class NotionRepository {

    private val auth = "Bearer ${SecretConfig.NOTION_API_KEY}"
    private val service = NotionClient.service

    /**
     * 指定したデータベースからUIDに一致するレコードを検索します。
     * 存在しない場合は、デフォルト値で新規作成して返します。
     */
    suspend fun findOrCreatePage(
        databaseId: String,
        pkColumnName: String,
        uid: String,
        defaultNameColumn: String,
        defaultNameValue: String
    ): NotionPage = withContext(Dispatchers.IO) {
        // 1. 検索
        val queryRes = service.queryDatabase(
            databaseId, auth,
            request = NotionQueryRequest(Filter(property = pkColumnName, title = TitleFilter(equals = uid)))
        )

        if (queryRes.results.isNotEmpty()) {
            queryRes.results[0]
        } else {
            // 2. なければ作成
            val props = mapOf(
                pkColumnName to mapOf("title" to listOf(mapOf("text" to mapOf("content" to uid)))),
                defaultNameColumn to mapOf("rich_text" to listOf(mapOf("text" to mapOf("content" to defaultNameValue))))
            )
            service.createPage(auth, request = NotionCreateRequest(Parent(databaseId), props))
        }
    }

    /**
     * 袋レコードの「現在の箱」リレーションを更新します。
     */
    suspend fun updateHukuroLocation(hukuroPageId: String, hakoPageId: String): NotionPage = withContext(Dispatchers.IO) {
        val updateProps = mapOf(
            "現在の箱" to mapOf("relation" to listOf(mapOf("id" to hakoPageId)))
        )
        service.updatePage(hukuroPageId, auth, request = NotionUpdateRequest(updateProps))
    }

    /**
     * 最新のページ情報を取得します（リレーション更新後の反映などに使用）。
     */
    suspend fun getPage(databaseId: String, pkColumnName: String, uid: String): NotionPage? = withContext(Dispatchers.IO) {
        val queryRes = service.queryDatabase(
            databaseId, auth,
            request = NotionQueryRequest(Filter(property = pkColumnName, title = TitleFilter(equals = uid)))
        )
        queryRes.results.firstOrNull()
    }
}
