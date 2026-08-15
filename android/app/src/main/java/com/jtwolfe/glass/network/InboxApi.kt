package com.jtwolfe.glass.network

import com.jtwolfe.glass.data.HealthResponse
import com.jtwolfe.glass.data.Message
import com.jtwolfe.glass.data.RepliesResponse
import com.jtwolfe.glass.data.SendMessageRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface InboxApi {
    @POST("v0/messages")
    suspend fun sendMessage(@Body request: SendMessageRequest): Response<Message>

    @GET("v0/replies")
    suspend fun getReplies(
        @Query("after") after: String,
        @Query("limit") limit: Int = 50
    ): Response<RepliesResponse>

    @GET("v0/health")
    suspend fun health(): Response<HealthResponse>
}
