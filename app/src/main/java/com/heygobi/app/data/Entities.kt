package com.heygobi.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, // spoken name, e.g. "good morning"
    val stepsJson: String, // JSON array of RoutineStep
    val createdAt: Long
)

@Entity(tableName = "command_log")
data class CommandLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String, // raw spoken text
    val action: String, // what the app did, e.g. "open:WhatsApp"
    val timestamp: Long,
    val success: Boolean
)
