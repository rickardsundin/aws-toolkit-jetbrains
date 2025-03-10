// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.amazonqFeatureDev.session

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import software.aws.toolkits.jetbrains.common.util.resolveAndCreateOrUpdateFile
import software.aws.toolkits.jetbrains.common.util.resolveAndDeleteFile
import software.aws.toolkits.jetbrains.services.amazonq.FeatureDevSessionContext
import software.aws.toolkits.jetbrains.services.amazonq.messages.MessagePublisher
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.CODE_GENERATION_RETRY_LIMIT
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.ConversationIdNotFoundException
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.FEATURE_NAME
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.MAX_PROJECT_SIZE_BYTES
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.clients.FeatureDevClient
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.messages.IncomingFeatureDevMessage
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.messages.sendAsyncEventProgress
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.messages.updateFileComponent
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.util.CancellationTokenSource
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.util.FeatureDevService
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.util.getChangeIdentifier
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.util.getDiffMetrics
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.util.readFileToString
import software.aws.toolkits.jetbrains.services.cwc.controller.ReferenceLogController
import java.util.HashSet

class Session(val tabID: String, val project: Project) {
    var context: FeatureDevSessionContext
    val sessionStartTime = System.currentTimeMillis()

    private var _state: SessionState?
    private var preloaderFinished: Boolean = false
    private var _conversationId: String? = null
    private var _latestMessage: String = ""
    private var task: String = ""
    private val proxyClient: FeatureDevClient
    private val featureDevService: FeatureDevService
    private var _codeResultMessageId: String? = null

    // retry session state vars
    private var codegenRetries: Int

    // Used to keep track of whether the current session/tab is currently authenticating/needs authenticating
    var isAuthenticating: Boolean

    init {
        context = FeatureDevSessionContext(project, MAX_PROJECT_SIZE_BYTES)
        proxyClient = FeatureDevClient.getInstance(project)
        featureDevService = FeatureDevService(proxyClient, project)
        _state = ConversationNotStartedState(
            approach = "",
            tabID = tabID,
            token = null,
            codeGenerationRemainingIterationCount = 0,
            codeGenerationTotalIterationCount = CODE_GENERATION_RETRY_LIMIT,
            currentIteration = 0,
            diffMetricsProcessed = DiffMetricsProcessed(HashSet(), HashSet())
        )
        isAuthenticating = false
        codegenRetries = CODE_GENERATION_RETRY_LIMIT
    }

    fun conversationIDLog(conversationId: String) = "$FEATURE_NAME Conversation ID: $conversationId"

    /**
     * Preload any events that have to run before a chat message can be sent
     */
    suspend fun preloader(msg: String, messenger: MessagePublisher) {
        if (!preloaderFinished) {
            setupConversation(msg, messenger)
            preloaderFinished = true
            messenger.sendAsyncEventProgress(tabId = this.tabID, inProgress = true)
            featureDevService.sendFeatureDevEvent(this.conversationId)
        }
    }

    /**
     * Starts a conversation with the backend and uploads the repo for the LLMs to be able to use it.
     */
    private fun setupConversation(msg: String, messenger: MessagePublisher) {
        // Store the initial message when setting up the conversation so that if it fails we can retry with this message
        _latestMessage = msg

        _conversationId = featureDevService.createConversation()
        logger<Session>().info(conversationIDLog(this.conversationId))

        val sessionStateConfig = getSessionStateConfig().copy(conversationId = this.conversationId)
        _state = PrepareCodeGenerationState(
            tabID = sessionState.tabID,
            approach = sessionState.approach,
            config = sessionStateConfig,
            filePaths = emptyList(),
            deletedFiles = emptyList(),
            references = emptyList(),
            currentIteration = 1, // first code gen iteration
            uploadId = "", // There is no code gen uploadId so far
            messenger = messenger,
            token = CancellationTokenSource(),
            diffMetricsProcessed = sessionState.diffMetricsProcessed,
        )
    }

    fun storeCodeResultMessageId(message: IncomingFeatureDevMessage.StoreMessageIdMessage) {
        val messageId = message.messageId
        this.updateCodeResultMessageId(messageId)
    }

    private fun updateCodeResultMessageId(messageId: String?) {
        this._codeResultMessageId = messageId
    }

    /**
     * Triggered by the Insert code follow-up button to apply code changes.
     */
    suspend fun insertChanges(
        filePaths: List<NewFileZipInfo>,
        deletedFiles: List<DeletedFileInfo>,
        references: List<CodeReferenceGenerated>,
        messenger: MessagePublisher,
    ) {
        val selectedSourceFolder = context.selectedSourceFolder.toNioPath()
        val newFilePaths = filePaths.filter { !it.rejected && !it.changeApplied }
        val newDeletedFiles = deletedFiles.filter { !it.rejected && !it.changeApplied }

        runCatching {
            var insertedLines = 0
            var insertedCharacters = 0
            filePaths.forEach { file ->
                // FIXME: Ideally, the before content should be read from the uploaded context instead of from disk, to avoid drift
                val before = selectedSourceFolder
                    .resolve(file.zipFilePath)
                    .toFile()
                    .let { f ->
                        if (f.exists() && f.canRead()) {
                            readFileToString(f)
                        } else {
                            ""
                        }
                    }

                val changeIdentifier = getChangeIdentifier(file.zipFilePath, before, file.fileContent)

                if (_state?.diffMetricsProcessed?.accepted?.contains(changeIdentifier) != true) {
                    val diffMetrics = getDiffMetrics(before, file.fileContent)
                    insertedLines += diffMetrics.insertedLines
                    insertedCharacters += diffMetrics.insertedCharacters
                    _state?.diffMetricsProcessed?.accepted?.add(changeIdentifier)
                }
            }

            if (insertedLines > 0) {
                featureDevService.sendFeatureDevCodeAcceptanceEvent(
                    conversationId = conversationId,
                    linesOfCodeAccepted = insertedLines,
                    charactersOfCodeAccepted = insertedCharacters,
                )
            }
        }.onFailure { /* Noop on diff telemetry failure */ }

        newFilePaths.forEach {
            resolveAndCreateOrUpdateFile(selectedSourceFolder, it.zipFilePath, it.fileContent)
            it.changeApplied = true
        }

        newDeletedFiles.forEach {
            resolveAndDeleteFile(selectedSourceFolder, it.zipFilePath)
            it.changeApplied = true
        }

        ReferenceLogController.addReferenceLog(references, project)

        // Taken from https://intellij-support.jetbrains.com/hc/en-us/community/posts/206118439-Refresh-after-external-changes-to-project-structure-and-sources
        VfsUtil.markDirtyAndRefresh(true, true, true, context.selectedSourceFolder)
        val codeResultMessageId = this._codeResultMessageId
        if (codeResultMessageId != null) {
            messenger.updateFileComponent(this.tabID, filePaths, deletedFiles, codeResultMessageId)
        }
    }

    suspend fun disableFileList(
        filePaths: List<NewFileZipInfo>,
        deletedFiles: List<DeletedFileInfo>,
        messenger: MessagePublisher,
    ) {
        if (this._codeResultMessageId.isNullOrEmpty()) {
            return
        }

        val codeResultMessageId = this._codeResultMessageId
        if (codeResultMessageId != null) {
            messenger.updateFileComponent(this.tabID, filePaths, deletedFiles, codeResultMessageId, disableFileActions = true)
        }
        this._codeResultMessageId = null
    }

    suspend fun send(msg: String): Interaction {
        // When the task/"thing to do" hasn't been set yet, we want it to be the incoming message
        if (task.isEmpty() && msg.isNotEmpty()) {
            task = msg
        }

        _latestMessage = msg
        return nextInteraction(msg)
    }

    private suspend fun nextInteraction(msg: String): Interaction {
        var action =
            SessionStateAction(
                task = task,
                msg = msg,
                token = sessionState.token,
            )
        val resp = sessionState.interact(action)
        if (resp.nextState != null) {
            // Approach may have been changed after the interaction
            val newApproach = sessionState.approach

            // Move to the next state
            _state = resp.nextState

            // If approach was changed then we need to set it in the next state and this state
            sessionState.approach = newApproach
        }
        return resp.interaction
    }

    private fun getSessionStateConfig(): SessionStateConfig = SessionStateConfig(
        conversationId = this.conversationId,
        repoContext = this.context,
        featureDevService = this.featureDevService,
    )

    val conversationId: String
        get() {
            if (_conversationId == null) {
                throw ConversationIdNotFoundException(operation = "Session", desc = "Conversation ID not found")
            } else {
                return _conversationId as String
            }
        }

    val conversationIdUnsafe: String?
        get() = _conversationId

    val sessionState: SessionState
        get() {
            if (_state == null) {
                throw Error("State should be initialized before it's read")
            } else {
                return _state as SessionState
            }
        }

    val latestMessage: String
        get() = this._latestMessage

    val retries: Int
        get() = codegenRetries

    fun decreaseRetries() {
        codegenRetries -= 1
    }
}
