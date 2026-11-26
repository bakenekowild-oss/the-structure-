package com.heygobi.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    fun getAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit")
    suspend fun getLast(limit: Int): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

@Dao
interface RoutineDao {
    @Insert
    suspend fun insert(routine: RoutineEntity): Long

    @Query("SELECT * FROM routines ORDER BY name ASC")
    fun getAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE lower(name) = lower(:name) LIMIT 1")
    suspend fun getByName(name: String): RoutineEntity?

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CommandLogDao {
    @Insert
    suspend fun insert(log: CommandLogEntity)

    @Query("SELECT * FROM command_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<CommandLogEntity>

    /**
     * Most frequent commands, for learning his habits and for proactive
     * suggestions ("Roju 9 ki WhatsApp open chesthav, open cheyyana?").
     */
    @Query(
        "SELECT command, COUNT(*) AS c FROM command_log " +
        "WHERE success = 1 GROUP BY command ORDER BY c DESC LIMIT :limit"
    )
    suspend fun getFrequentCommands(limit: Int): List<FrequentCommand>

    @Query("SELECT * FROM command_log WHERE command = :command ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastForCommand(command: String): CommandLogEntity?
}

data class FrequentCommand(val command: String, val c: Int)
