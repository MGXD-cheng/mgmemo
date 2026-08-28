package com.mgmemo.app.data

import retrofit2.http.Body
import retrofit2.http.POST

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>
)

data class ChatResponse(
    val choices: List<Choice> = emptyList()
) {
    data class Choice(
        val message: ChatMessage? = null
    )
}

interface AiApi {

    @POST("chat/completions")
    suspend fun chatCompletion(@Body body: ChatRequest): ChatResponse
}
