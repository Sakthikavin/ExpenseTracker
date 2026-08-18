package com.example.expensetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.expensetracker.data.local.entity.MerchantCategoryRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantCategoryRuleDao {
    @Query("SELECT * FROM merchant_category_rules ORDER BY merchantKey")
    fun observeAll(): Flow<List<MerchantCategoryRuleEntity>>

    @Query("SELECT * FROM merchant_category_rules WHERE merchantKey = :merchantKey LIMIT 1")
    suspend fun getByKey(merchantKey: String): MerchantCategoryRuleEntity?

    @Query("SELECT * FROM merchant_category_rules WHERE id = :id")
    suspend fun getById(id: Long): MerchantCategoryRuleEntity?

    @Query("SELECT COUNT(*) FROM merchant_category_rules WHERE categoryId = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: MerchantCategoryRuleEntity): Long

    @Update
    suspend fun update(rule: MerchantCategoryRuleEntity)

    @Query("DELETE FROM merchant_category_rules WHERE id = :id")
    suspend fun delete(id: Long)
}
