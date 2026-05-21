package com.messenger.app.network

import com.messenger.app.models.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("auth/register") suspend fun register(@Body r: AuthRequest): Response<AuthResponse>
    @POST("auth/login")    suspend fun login(@Body r: AuthRequest): Response<AuthResponse>
    @POST("auth/logout")   suspend fun logout(): Response<Unit>

    @GET("users/me")       suspend fun getMe(): Response<User>
    @PATCH("users/me")     suspend fun updateProfile(@Body r: UpdateProfileRequest): Response<Unit>
    @Multipart @POST("users/me/avatar")
                           suspend fun uploadAvatar(@Part f: MultipartBody.Part): Response<AvatarResponse>
    @GET("users/search")   suspend fun searchUsers(@Query("q") q: String): Response<List<User>>
    @GET("users/{id}")     suspend fun getUser(@Path("id") id: Int): Response<User>

    @GET("rooms")          suspend fun getRooms(): Response<List<Room>>
    @POST("rooms")         suspend fun createRoom(@Body r: CreateRoomRequest): Response<CreateRoomResponse>
    @GET("rooms/{id}")     suspend fun getRoom(@Path("id") id: Int): Response<Room>
    @POST("rooms/join/{invite}") suspend fun joinByInvite(@Path("invite") invite: String): Response<CreateRoomResponse>
    @POST("rooms/{id}/members/{uid}") suspend fun addMember(@Path("id") id: Int, @Path("uid") uid: Int): Response<Unit>
    @DELETE("rooms/{id}/members/{uid}") suspend fun removeMember(@Path("id") id: Int, @Path("uid") uid: Int): Response<Unit>

    @GET("rooms/{id}/messages")
    suspend fun getMessages(@Path("id") id: Int, @Query("before") before: Int? = null,
                            @Query("limit") limit: Int = 50): Response<List<Message>>
    @POST("rooms/{id}/messages")
    suspend fun sendMessage(@Path("id") id: Int, @Body r: SendMessageRequest): Response<Message>
    @Multipart @POST("rooms/{id}/messages/upload")
    suspend fun uploadFile(@Path("id") id: Int, @Part f: MultipartBody.Part): Response<Message>
    @PATCH("rooms/{rid}/messages/{mid}")
    suspend fun editMessage(@Path("rid") rid: Int, @Path("mid") mid: Int, @Body r: EditMessageRequest): Response<Unit>
    @DELETE("rooms/{rid}/messages/{mid}")
    suspend fun deleteMessage(@Path("rid") rid: Int, @Path("mid") mid: Int): Response<Unit>
    @POST("rooms/{rid}/messages/{mid}/pin")
    suspend fun pinMessage(@Path("rid") rid: Int, @Path("mid") mid: Int): Response<Unit>
    @POST("rooms/{rid}/messages/{mid}/react")
    suspend fun react(@Path("rid") rid: Int, @Path("mid") mid: Int, @Body r: ReactionRequest): Response<Unit>

    @POST("calls/init")        suspend fun initCall(@Body r: InitCallRequest): Response<InitCallResponse>
    @POST("calls/{id}/accept") suspend fun acceptCall(@Path("id") id: Int): Response<Unit>
    @POST("calls/{id}/end")    suspend fun endCall(@Path("id") id: Int): Response<Unit>
    @POST("calls/signal")      suspend fun sendSignal(@Body r: CallSignalRequest): Response<Unit>
}

    // E2E
    @POST("users/me/public_key")
    suspend fun uploadPublicKey(@Body r: com.messenger.app.models.PublicKeyRequest): Response<Unit>
