package com.ai.assistance.operit.integrations.http

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.MimeTypeMap
import com.ai.assistance.operit.R
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser
import com.ai.assistance.operit.api.chat.llmprovider.ThinkingQualityControl
import com.ai.assistance.operit.api.chat.llmprovider.ThinkingQualityMapping
import com.ai.assistance.operit.api.chat.llmprovider.ThinkingQualityMappingRegistry
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageLocatorPreview
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.CharacterCardChatModelBindingMode
import com.ai.assistance.operit.data.model.CharacterGroupCard
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.getModelByIndex
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.getValidModelIndex
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.ExternalHttpApiPreferences
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.ThemePreferenceSnapshot
import com.ai.assistance.operit.data.preferences.ToolCollapseMode
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.integrations.http.bridge.WebChatActionBridge
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.integrations.http.bridge.WebChatInputSettingsBridge
import com.ai.assistance.operit.integrations.http.bridge.WebChatManagementBridge
import com.ai.assistance.operit.integrations.externalchat.ExternalChatResponseSanitizer
import com.ai.assistance.operit.services.core.MAX_DISPLAY_PAGE_COUNT
import com.ai.assistance.operit.services.core.resolveDisplayPageRanges
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.StructuredAssistantContentParser
import com.ai.assistance.operit.ui.theme.resolveThemeColorScheme
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLConnection
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.ai.assistance.operit.util.stream.SharedStream
import androidx.compose.ui.graphics.toArgb

class WebChatHttpBridge(
    context: Context,
    private val preferences: ExternalHttpApiPreferences,
    private val serviceScope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val runtimeHolder = ChatRuntimeHolder.getInstance(appContext)
    private val core = runtimeHolder.getCore(ChatRuntimeSlot.MAIN)
    private val chatHistoryManager = ChatHistoryManager.getInstance(appContext)
    private val userPreferencesManager = UserPreferencesManager.getInstance(appContext)
    private val displayPreferencesManager = DisplayPreferencesManager.getInstance(appContext)
    private val activePromptManager = ActivePromptManager.getInstance(appContext)
    private val characterCardManager = CharacterCardManager.getInstance(appContext)
    private val characterGroupCardManager = CharacterGroupCardManager.getInstance(appContext)
    private val functionalConfigManager = FunctionalConfigManager(appContext)
    private val modelConfigManager = ModelConfigManager(appContext)
    private val inputSettingsBridge = WebChatInputSettingsBridge(appContext, core)
    private val actionBridge = WebChatActionBridge(core)
    private val chatManagementBridge =
        WebChatManagementBridge(core, chatHistoryManager, activePromptManager)
    private val assetIdBySource = ConcurrentHashMap<String, String>()
    private val assetsById = ConcurrentHashMap<String, RegisteredAsset>()
    private val uploadsById = ConcurrentHashMap<String, UploadedAttachmentEntry>()

    fun handleApi(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        cleanupExpiredEntries()

        if (session.uri.startsWith(ASSET_ROUTE_PREFIX)) {
            return handleRegisteredAsset(session).withCors()
        }

        val unauthorized = requireBearerToken(session)
        if (unauthorized != null) {
            return unauthorized
        }

        return when {
            session.uri == BOOTSTRAP_PATH && session.method == NanoHTTPD.Method.GET ->
                handleBootstrap()

            session.uri == CHARACTER_SELECTOR_PATH && session.method == NanoHTTPD.Method.GET ->
                handleCharacterSelector()

            session.uri == ACTIVE_PROMPT_PATH && session.method == NanoHTTPD.Method.POST ->
                handleSetActivePrompt(session)

            session.uri == MODEL_SELECTOR_PATH && session.method == NanoHTTPD.Method.GET ->
                handleModelSelector()

            session.uri == MODEL_SELECTOR_PATH && session.method == NanoHTTPD.Method.POST ->
                handleSelectModel(session)



            session.uri == INPUT_SETTINGS_PATH && session.method == NanoHTTPD.Method.GET ->
                handleInputSettings()

            session.uri == INPUT_SETTINGS_PATH && session.method == NanoHTTPD.Method.PATCH ->
                handleUpdateInputSettings(session)


            session.uri == MANUAL_CONVERSATION_SUMMARY_PATH && session.method == NanoHTTPD.Method.POST ->
                handleManualConversationSummary()

            session.uri == CHATS_PATH && session.method == NanoHTTPD.Method.GET ->
                handleListChats()

            session.uri == CHATS_PATH && session.method == NanoHTTPD.Method.POST ->
                handleCreateChat(session)

            session.uri == CHATS_REORDER_PATH && session.method == NanoHTTPD.Method.POST ->
                handleReorderChats(session)

            session.uri == CHAT_GROUP_RENAME_PATH && session.method == NanoHTTPD.Method.POST ->
                handleRenameGroup(session)

            session.uri == CHAT_GROUP_DELETE_PATH && session.method == NanoHTTPD.Method.POST ->
                handleDeleteGroup(session)

            chatIdFrom(session.uri, CHATS_PATH)?.let { chatId ->
                session.uri == "$CHATS_PATH/$chatId"
            } == true && session.method == NanoHTTPD.Method.PATCH ->
                handleUpdateChat(session, requireNotNull(chatIdFrom(session.uri, CHATS_PATH)))

            chatIdFrom(session.uri, CHATS_PATH)?.let { chatId ->
                session.uri == "$CHATS_PATH/$chatId"
            } == true && session.method == NanoHTTPD.Method.DELETE ->
                handleDeleteChat(requireNotNull(chatIdFrom(session.uri, CHATS_PATH)))

            chatIdFrom(session.uri, "$CHATS_PATH/", "/select") != null &&
                session.method == NanoHTTPD.Method.POST ->
                handleSelectChat(
                    requireNotNull(chatIdFrom(session.uri, "$CHATS_PATH/", "/select"))
                )

            chatIdFrom(session.uri, "$CHATS_PATH/", "/messages") != null &&
                session.method == NanoHTTPD.Method.GET ->
                handleMessages(
                    session,
                    requireNotNull(chatIdFrom(session.uri, "$CHATS_PATH/", "/messages"))
                )

            chatIdFrom(session.uri, "$CHATS_PATH/", "/message-locator") != null &&
                session.method == NanoHTTPD.Method.GET ->
                handleMessageLocator(
                    session,
                    requireNotNull(chatIdFrom(session.uri, "$CHATS_PATH/", "/message-locator"))
                )

            chatIdFrom(session.uri, "$CHATS_PATH/", "/messages/reveal") != null &&
                session.method == NanoHTTPD.Method.POST ->
                handleRevealMessage(
                    session,
                    requireNotNull(chatIdFrom(session.uri, "$CHATS_PATH/", "/messages/reveal"))
                )

            chatIdFrom(session.uri, "$CHATS_PATH/", "/messages/favorite") != null &&
                session.method == NanoHTTPD.Method.PATCH ->
                handleToggleMessageFavorite(
                    session,
                    requireNotNull(chatIdFrom(session.uri, "$CHATS_PATH/", "/messages/favorite"))
                )

            chatIdFrom(session.uri, "$CHATS_PATH/", "/theme") != null &&
                session.method == NanoHTTPD.Method.GET ->
                handleTheme(
                    requireNotNull(chatIdFrom(session.uri, "$CHATS_PATH/", "/theme"))
                )

            chatIdFrom(session.uri, "$CHATS_PATH/", "/messages/stream") != null &&
                session.method == NanoHTTPD.Method.POST ->
                handleStream(
                    session,
                    requireNotNull(chatIdFrom(session.uri, "$CHATS_PATH/", "/messages/stream"))
                )

            session.uri == UPLOADS_PATH && session.method == NanoHTTPD.Method.POST ->
                handleUpload(session)

            else ->
                jsonResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    WebErrorResponse("API endpoint not found")
                )
        }.withCors()
    }

    fun serveStatic(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        cleanupExpiredEntries()

        if (session.method != NanoHTTPD.Method.GET && session.method != NanoHTTPD.Method.HEAD) {
            return plainTextResponse(
                NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                "Method not allowed"
            ).withCors()
        }

        val requestedPath = normalizeStaticPath(session.uri)
            ?: return plainTextResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                "Access denied"
            ).withCors()

        val assetPath = resolvePackagedAssetPath(requestedPath)
        val staticAsset = openPackagedAsset(assetPath)
        if (staticAsset == null) {
            return plainTextResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "Web assets are not available in app assets/web-chat"
            ).withCors()
        }

        return byteArrayResponse(
            status = NanoHTTPD.Response.Status.OK,
            mimeType = staticAsset.mimeType,
            bytes = staticAsset.bytes
        ).withCors()
    }

    private fun handleBootstrap(): NanoHTTPD.Response {
        val currentChatId = core.currentChatId.value
        val snapshot = runBlocking {
            val chat = if (currentChatId == null) null else currentChatMeta(currentChatId)
            resolveThemePreferenceSnapshot(chat)
        }
        return jsonResponse(
            NanoHTTPD.Response.Status.OK,
            WebBootstrapResponse(
                versionName = BuildConfig.VERSION_NAME,
                currentChatId = currentChatId,
                defaultChatStyle = snapshot.chatStyle,
                defaultInputStyle = snapshot.inputStyle,
                showThinkingProcess = snapshot.showThinkingProcess,
                showStatusTags = snapshot.showStatusTags,
                showInputProcessingStatus = snapshot.showInputProcessingStatus,
                capabilities = WebCapabilities(
                    attachments = true,
                    perChatTheme = true,
                    structuredRender = true,
                    streaming = true,
                    renameChat = true,
                    deleteChat = true
                )
            )
        )
    }

    private fun handleCharacterSelector(): NanoHTTPD.Response {
        val response = runBlocking { buildCharacterSelectorResponse() }
        return jsonResponse(NanoHTTPD.Response.Status.OK, response)
    }

    private fun handleSetActivePrompt(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = parseJsonRequest<WebSetActivePromptRequest>(session)
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Invalid JSON body")
            )

        val targetType = request.type.trim().lowercase(Locale.US)
        val targetId = request.id.trim()
        if (targetId.isBlank()) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Missing active prompt id")
            )
        }

        val response = runBlocking {
            when (targetType) {
                ACTIVE_PROMPT_TYPE_CHARACTER_CARD -> {
                    val cardExists = characterCardManager.getAllCharacterCards().any { it.id == targetId }
                    if (!cardExists) {
                        return@runBlocking null
                    }
                    activePromptManager.setActivePrompt(ActivePrompt.CharacterCard(targetId))
                }

                ACTIVE_PROMPT_TYPE_CHARACTER_GROUP -> {
                    val groupExists = characterGroupCardManager.getCharacterGroupCard(targetId) != null
                    if (!groupExists) {
                        return@runBlocking null
                    }
                    activePromptManager.setActivePrompt(ActivePrompt.CharacterGroup(targetId))
                }

                else -> return@runBlocking null
            }

            buildCharacterSelectorResponse()
        }

        return when {
            targetType != ACTIVE_PROMPT_TYPE_CHARACTER_CARD &&
                targetType != ACTIVE_PROMPT_TYPE_CHARACTER_GROUP ->
                jsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    WebErrorResponse("Unsupported active prompt type: $targetType")
                )

            response == null ->
                jsonResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    WebErrorResponse("Active prompt target not found")
                )

            else ->
                jsonResponse(NanoHTTPD.Response.Status.OK, response)
        }
    }

    private fun handleListChats(): NanoHTTPD.Response {
        val chats = runBlocking {
            val histories = chatHistoryManager.chatHistoriesFlow.first()
            val characterGroupNamesById = resolveCharacterGroupNames(histories)
            val bindingAvatarUrlByChatId = resolveBindingAvatarUrls(histories)

            histories.map { history ->
                buildChatSummary(
                    history = history,
                    characterGroupName = history.characterGroupId?.let(characterGroupNamesById::get),
                    bindingAvatarUrl = bindingAvatarUrlByChatId[history.id]
                )
            }
        }
        return jsonResponse(NanoHTTPD.Response.Status.OK, chats)
    }

    private fun handleModelSelector(): NanoHTTPD.Response {
        val selector = runBlocking { resolveModelSelectorState() }
        return jsonResponse(NanoHTTPD.Response.Status.OK, selector)
    }

    private fun handleSelectModel(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = parseJsonRequest<WebSelectModelRequest>(session)
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Invalid JSON body")
            )

        val selectedId = request.configId.trim()
        if (selectedId.isBlank()) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Missing config_id")
            )
        }

        val response = runBlocking {
            val selectorBefore = resolveModelSelectorState()
            val targetConfig = selectorBefore.configs.firstOrNull { it.id == selectedId }
                ?: return@runBlocking null
            val normalizedModelIndex = if (targetConfig.models.isEmpty()) {
                0
            } else {
                getValidModelIndex(targetConfig.modelName, request.modelIndex)
            }
            val isSameSelection =
                selectorBefore.currentConfigId == selectedId &&
                    selectorBefore.currentModelIndex == normalizedModelIndex

            if (
                selectorBefore.lockedByCharacterCard &&
                    !isSameSelection &&
                    !request.confirmCharacterCardSwitch
            ) {
                return@runBlocking WebSelectModelResponse(
                    success = false,
                    requiresCharacterCardSwitchConfirmation = true,
                    selector = selectorBefore
                )
            }

            if (!isSameSelection) {
                if (selectorBefore.lockedByCharacterCard) {
                    val activePrompt = activePromptManager.getActivePrompt()
                    if (activePrompt !is ActivePrompt.CharacterCard) {
                        return@runBlocking null
                    }
                    val activeCard = characterCardManager.getCharacterCard(activePrompt.id)
                    characterCardManager.updateCharacterCard(
                        activeCard.copy(
                            chatModelBindingMode = CharacterCardChatModelBindingMode.FIXED_CONFIG,
                            chatModelConfigId = selectedId,
                            chatModelIndex = normalizedModelIndex
                        )
                    )
                } else {
                    functionalConfigManager.setConfigForFunction(
                        FunctionType.CHAT,
                        selectedId,
                        normalizedModelIndex
                    )
                }
                EnhancedAIService.refreshServiceForFunction(appContext, FunctionType.CHAT)
            }

            WebSelectModelResponse(
                success = true,
                selector = resolveModelSelectorState()
            )
        }

        return if (response == null) {
            jsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                WebErrorResponse("Model config not found")
            )
        } else {
            jsonResponse(NanoHTTPD.Response.Status.OK, response)
        }
    }

    private fun handleCreateChat(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = parseJsonRequest<WebCreateChatRequest>(session)
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Invalid JSON body")
            )

        val created = runBlocking {
            val newChat = chatHistoryManager.createNewChat(
                group = request.group?.trim()?.takeIf { it.isNotBlank() },
                characterCardName = request.characterCardName?.trim()?.takeIf { it.isNotBlank() },
                characterGroupId = request.characterGroupId?.trim()?.takeIf { it.isNotBlank() },
                setAsCurrentChat = request.setCurrent
            )
            val normalizedTitle = request.title?.trim()?.takeIf { it.isNotBlank() }
            if (normalizedTitle != null) {
                chatHistoryManager.updateChatTitle(newChat.id, normalizedTitle)
            }
            if (request.setCurrent) {
                switchAppChatContext(newChat.id)
            }
            val updatedChat = currentChatMeta(newChat.id) ?: newChat
            buildChatSummary(updatedChat)
        }

        return jsonResponse(NanoHTTPD.Response.Status.OK, created)
    }

    private fun handleInputSettings(): NanoHTTPD.Response {
        val settings = runBlocking { inputSettingsBridge.resolveState() }
        return jsonResponse(NanoHTTPD.Response.Status.OK, settings)
    }



    private fun handleUpdateInputSettings(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = parseJsonRequest<WebUpdateInputSettingsRequest>(session)
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Invalid JSON body")
            )

        val updated = runBlocking { inputSettingsBridge.update(request) }
        return jsonResponse(NanoHTTPD.Response.Status.OK, updated)
    }


    private fun handleManualConversationSummary(): NanoHTTPD.Response {
        actionBridge.manuallySummarizeConversation()
        return jsonResponse(
            NanoHTTPD.Response.Status.OK,
            WebActionResponse(
                success = true,
                chatId = core.currentChatId.value
            )
        )
    }

    private fun handleUpdateChat(
        session: NanoHTTPD.IHTTPSession,
        chatId: String
    ): NanoHTTPD.Response {
        if (!runBlocking { chatHistoryManager.chatExists(chatId) }) {
            return jsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                WebErrorResponse("Chat not found")
            )
        }

        val request = parseJsonRequest<WebUpdateChatRequest>(session)
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Invalid JSON body")
            )
        val hasTitleChange = request.title != null
        val hasGroupChange = request.updateGroup
        val hasLockedChange = request.updateLocked && request.locked != null
        val hasPinnedChange = request.updatePinned && request.pinned != null
        val hasBindingChange = request.updateBinding
        if (!hasTitleChange && !hasGroupChange && !hasLockedChange && !hasPinnedChange && !hasBindingChange) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("No update fields provided")
            )
        }

        val normalizedTitle = request.title?.trim()?.takeIf { it.isNotBlank() }
        if (hasTitleChange && normalizedTitle == null) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Missing title")
            )
        }

        val normalizedCharacterCardName = request.characterCardName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedCharacterGroupId = request.characterGroupId?.trim()?.takeIf { it.isNotBlank() }
        if (hasBindingChange && normalizedCharacterCardName != null && normalizedCharacterGroupId != null) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Chat binding cannot target both a character card and a character group")
            )
        }

        val updated = runBlocking {
            chatManagementBridge.updateChat(
                chatId = chatId,
                request = request,
                currentChatMeta = ::currentChatMeta,
                buildChatSummary = ::buildChatSummary
            )
        }
        return if (updated != null) {
            jsonResponse(NanoHTTPD.Response.Status.OK, updated)
        } else {
            jsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                WebErrorResponse("Chat not found")
            )
        }
    }

    private fun handleReorderChats(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = parseJsonRequest<WebReorderChatsRequest>(session)
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Invalid JSON body")
            )
        if (request.items.isEmpty()) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("No reorder items provided")
            )
        }

        val success = runBlocking { chatManagementBridge.reorderChats(request.items) }

        return if (success) {
            jsonResponse(
                NanoHTTPD.Response.Status.OK,
                WebActionResponse(success = true)
            )
        } else {
            jsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                WebErrorResponse("One or more chats could not be found")
            )
        }
    }

    private fun handleRenameGroup(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = parseJsonRequest<WebRenameGroupRequest>(session)
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Invalid JSON body")
            )
        val oldName = request.oldName.trim()
        val newName = request.newName.trim()
        if (oldName.isBlank() || newName.isBlank()) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Group names must not be blank")
            )
        }

        runBlocking { chatManagementBridge.renameGroup(request) }
        return jsonResponse(
            NanoHTTPD.Response.Status.OK,
            WebActionResponse(success = true)
        )
    }

    private fun handleDeleteGroup(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = parseJsonRequest<WebDeleteGroupRequest>(session)
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Invalid JSON body")
            )
        val groupName = request.groupName.trim()
        if (groupName.isBlank()) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Group name must not be blank")
            )
        }

        runBlocking { chatManagementBridge.deleteGroup(request) }
        return jsonResponse(
            NanoHTTPD.Response.Status.OK,
            WebActionResponse(success = true)
        )
    }

    private fun handleSelectChat(chatId: String): NanoHTTPD.Response {
        if (!runBlocking { chatHistoryManager.chatExists(chatId) }) {
            return jsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                WebErrorResponse("Chat not found")
            )
        }

        val switched = runBlocking { switchAppChatContext(chatId) }
        return if (switched) {
            jsonResponse(
                NanoHTTPD.Response.Status.OK,
                WebActionResponse(success = true, chatId = chatId)
            )
        } else {
            jsonResponse(
                NanoHTTPD.Response.Status.CONFLICT,
                WebErrorResponse("Failed to switch chat context")
            )
        }
    }

    private fun handleDeleteChat(chatId: String): NanoHTTPD.Response {
        if (!runBlocking { chatHistoryManager.chatExists(chatId) }) {
            return jsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                WebErrorResponse("Chat not found")
            )
        }

        if (!runBlocking { chatHistoryManager.canDeleteChatHistory(chatId) }) {
            return jsonResponse(
                NanoHTTPD.Response.Status.CONFLICT,
                WebErrorResponse("Chat is locked and cannot be deleted")
            )
        }

        if (core.activeStreamingChatIds.value.contains(chatId)) {
            runBlocking { core.cancelMessageForDestructiveMutation(chatId) }
        }

        val deleted = runBlocking { chatHistoryManager.deleteChatHistory(chatId) }
        return if (deleted) {
            jsonResponse(
                NanoHTTPD.Response.Status.OK,
                WebActionResponse(success = true, chatId = chatId, deleted = true)
            )
        } else {
            jsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                WebErrorResponse("Failed to delete chat")
            )
        }
    }

    private fun handleMessages(
        session: NanoHTTPD.IHTTPSession,
        chatId: String
    ): NanoHTTPD.Response {
        if (!runBlocking { chatHistoryManager.chatExists(chatId) }) {
            return jsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                WebErrorResponse("Chat not found")
            )
        }

        val limit = session.parameters["limit"]
            ?.firstOrNull()
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_MESSAGES_PAGE_SIZE)
            ?: DEFAULT_MESSAGES_PAGE_SIZE
        val beforeTimestamp = session.parameters["before_timestamp"]
            ?.firstOrNull()
            ?.toLongOrNull()
        val afterTimestamp = session.parameters["after_timestamp"]
            ?.firstOrNull()
            ?.toLongOrNull()
        if (beforeTimestamp != null && afterTimestamp != null) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("before_timestamp and after_timestamp cannot both be provided")
            )
        }

        val page = runBlocking {
            val structuredRenderPreferences = resolveStructuredRenderPreferences(chatId)
            when {
                beforeTimestamp != null -> {
                    val fetchedMessages = chatHistoryManager.loadOlderChatMessages(
                        chatId = chatId,
                        beforeTimestampExclusive = beforeTimestamp,
                        limit = limit + 1
                    )
                    val hasMoreBefore = fetchedMessages.size > limit
                    val pageMessages = if (hasMoreBefore) fetchedMessages.takeLast(limit) else fetchedMessages
                    val hasMoreAfter = pageMessages.lastOrNull()?.timestamp?.let {
                        chatHistoryManager.hasMessagesAfter(chatId, it)
                    } ?: false
                    WebChatMessagesPage(
                        messages = pageMessages.map { message ->
                            buildWebMessage(chatId, message, structuredRenderPreferences = structuredRenderPreferences)
                        },
                        hasMoreBefore = hasMoreBefore,
                        hasMoreAfter = hasMoreAfter,
                        nextBeforeTimestamp = pageMessages.firstOrNull()?.timestamp,
                        nextAfterTimestamp = pageMessages.lastOrNull()?.timestamp
                    )
                }

                afterTimestamp != null -> {
                    val fetchedMessages = chatHistoryManager.loadChatMessagesAscAfter(
                        chatId = chatId,
                        afterTimestampExclusive = afterTimestamp,
                        limit = limit + 1
                    )
                    val hasMoreAfter = fetchedMessages.size > limit
                    val pageMessages = fetchedMessages.take(limit)
                    val hasMoreBefore = pageMessages.firstOrNull()?.timestamp?.let {
                        chatHistoryManager.hasMessagesBefore(chatId, it)
                    } ?: false
                    WebChatMessagesPage(
                        messages = pageMessages.map { message ->
                            buildWebMessage(chatId, message, structuredRenderPreferences = structuredRenderPreferences)
                        },
                        hasMoreBefore = hasMoreBefore,
                        hasMoreAfter = hasMoreAfter,
                        nextBeforeTimestamp = pageMessages.firstOrNull()?.timestamp,
                        nextAfterTimestamp = pageMessages.lastOrNull()?.timestamp
                    )
                }

                else -> {
                    val fetchedMessagesDesc = chatHistoryManager.loadChatMessagesDesc(
                        chatId = chatId,
                        limit = limit + 1
                    )
                    val hasMoreBefore = fetchedMessagesDesc.size > limit
                    val pageMessages =
                        fetchedMessagesDesc
                            .take(limit)
                            .asReversed()
                    WebChatMessagesPage(
                        messages = pageMessages.map { message ->
                            buildWebMessage(chatId, message, structuredRenderPreferences = structuredRenderPreferences)
                        },
                        hasMoreBefore = hasMoreBefore,
                        hasMoreAfter = false,
                        nextBeforeTimestamp = pageMessages.firstOrNull()?.timestamp,
                        nextAfterTimestamp = pageMessages.lastOrNull()?.timestamp
                    )
                }
            }
        }
        return jsonResponse(NanoHTTPD.Response.Status.OK, page)
    }

    private fun handleMessageLocator(
        session: NanoHTTPD.IHTTPSession,
        chatId: String
    ): NanoHTTPD.Response {
        if (!runBlocking { chatHistoryManager.chatExists(chatId) }) {
            return jsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                WebErrorResponse("Chat not found")
            )
        }

        val query = session.parameters["query"]?.firstOrNull().orEmpty()
        val previews = runBlocking {
            chatHistoryManager.loadChatMessageLocatorPreviews(chatId, query)
                .map(::buildWebMessageLocatorPreview)
        }
        return jsonResponse(NanoHTTPD.Response.Status.OK, previews)
    }

    private fun handleRevealMessage(
        session: NanoHTTPD.IHTTPSession,
        chatId: String
    ): NanoHTTPD.Response {
        if (!runBlocking { chatHistoryManager.chatExists(chatId) }) {
            return jsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                WebErrorResponse("Chat not found")
            )
        }

        val request = parseJsonRequest<WebRevealMessageRequest>(session)
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Invalid JSON body")
            )
        val targetTimestamp = request.timestamp
            ?: return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                WebErrorResponse("Missing timestamp")
            )

        val page = runBlocking {
            val locatorEntries = chatHistoryManager.loadChatMessageLocatorPreviews(chatId)
            val pageRanges = resolveDisplayPageRanges(locatorEntries)
            val targetPageIndex =
                pageRanges.indexOfFirst { range ->
                    targetTimestamp in range.startTimestampInclusive..range.endTimestampInclusive
                }
            if (targetPageIndex < 0) {
                null
            