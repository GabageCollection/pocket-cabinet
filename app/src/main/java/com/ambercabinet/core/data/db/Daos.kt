package com.ambercabinet.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BottleDao {
    @Query("SELECT * FROM bottles WHERE deleted = 0")
    fun observeAll(): Flow<List<BottleEntity>>

    @Query("SELECT * FROM bottles WHERE deleted = 0")
    suspend fun getAll(): List<BottleEntity>

    @Query("SELECT * FROM bottles WHERE id = :id")
    suspend fun getById(id: String): BottleEntity?

    @Query("SELECT * FROM bottles WHERE ingredientId = :ingredientId AND deleted = 0")
    suspend fun getByIngredient(ingredientId: String): List<BottleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bottle: BottleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bottles: List<BottleEntity>)

    @Query("DELETE FROM bottles")
    suspend fun clear()
}

@Dao
interface TxnDao {
    @Query("SELECT * FROM transactions ORDER BY time DESC")
    fun observeAll(): Flow<List<TxnEntity>>

    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<TxnEntity>

    @Query("SELECT * FROM transactions WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: String): List<TxnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(txn: TxnEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(txns: List<TxnEntity>)

    @Query("UPDATE transactions SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: String)

    @Query("DELETE FROM transactions")
    suspend fun clear()
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM mix_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM mix_sessions")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM mix_sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("DELETE FROM mix_sessions")
    suspend fun clear()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM tasting_notes")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM tasting_notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM tasting_notes WHERE sessionId = :sessionId LIMIT 1")
    suspend fun forSession(sessionId: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Query("DELETE FROM tasting_notes")
    suspend fun clear()
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites")
    suspend fun getAll(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(fav: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE recipeId = :recipeId")
    suspend fun remove(recipeId: String)

    @Query("DELETE FROM favorites")
    suspend fun clear()
}

@Dao
interface CustomRecipeDao {
    @Query("SELECT * FROM custom_recipes WHERE deleted = 0")
    fun observeAll(): Flow<List<CustomRecipeEntity>>

    @Query("SELECT * FROM custom_recipes")
    suspend fun getAll(): List<CustomRecipeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipe: CustomRecipeEntity)

    @Query("UPDATE custom_recipes SET deleted = 1, updatedAt = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(recipes: List<CustomRecipeEntity>)

    @Query("DELETE FROM custom_recipes")
    suspend fun clear()
}

@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM kv")
    suspend fun getAll(): List<KvEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: KvEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entities: List<KvEntity>)

    @Query("DELETE FROM kv")
    suspend fun clear()
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM mix_drafts WHERE id = :id")
    suspend fun getById(id: String): DraftEntity?

    @Query("SELECT * FROM mix_drafts ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(): Flow<DraftEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM mix_drafts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM mix_drafts")
    suspend fun getAll(): List<DraftEntity>

    @Query("DELETE FROM mix_drafts")
    suspend fun clear()
}

@Dao
interface CustomIngredientDao {
    @Query("SELECT * FROM custom_ingredients WHERE deleted = 0")
    fun observeAll(): Flow<List<CustomIngredientEntity>>

    @Query("SELECT * FROM custom_ingredients")
    suspend fun getAll(): List<CustomIngredientEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CustomIngredientEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CustomIngredientEntity>)

    @Query("UPDATE custom_ingredients SET deleted = 1, updatedAt = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Query("DELETE FROM custom_ingredients")
    suspend fun clear()
}
