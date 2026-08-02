package com.example.expensetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.expensetracker.data.local.entity.OwnAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OwnAccountDao {
    @Query("SELECT * FROM own_accounts ORDER BY nickname, label")
    fun observeAll(): Flow<List<OwnAccountEntity>>

    @Query("SELECT * FROM own_accounts")
    suspend fun getAll(): List<OwnAccountEntity>

    /**
     * Every account label seen in a transaction, whether or not the user has claimed it. The
     * "My accounts" screen offers these as toggles so nobody has to type an account number.
     */
    @Query(
        """
        SELECT DISTINCT accountLabel FROM transactions
        WHERE accountLabel != '' ORDER BY accountLabel
        """,
    )
    fun observeSeenLabels(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: OwnAccountEntity): Long

    @Update
    suspend fun update(account: OwnAccountEntity)

    @Delete
    suspend fun delete(account: OwnAccountEntity)

    @Query("DELETE FROM own_accounts WHERE label = :label")
    suspend fun deleteByLabel(label: String)
}
