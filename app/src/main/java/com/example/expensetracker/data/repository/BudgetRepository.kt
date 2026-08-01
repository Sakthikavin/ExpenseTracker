package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.dao.BudgetDao
import com.example.expensetracker.data.local.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

class BudgetRepository(private val budgetDao: BudgetDao) {
    fun observeAll(): Flow<List<BudgetEntity>> = budgetDao.observeAll()

    suspend fun getForCategory(categoryId: Long): BudgetEntity? = budgetDao.getForCategory(categoryId)

    suspend fun upsert(budget: BudgetEntity): Long = budgetDao.upsert(budget)

    suspend fun delete(id: Long) = budgetDao.delete(id)
}