package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.dao.BillDao
import com.example.expensetracker.data.local.entity.BillEntity
import kotlinx.coroutines.flow.Flow

class BillRepository(private val billDao: BillDao) {
    fun observeAll(): Flow<List<BillEntity>> = billDao.observeAll()

    suspend fun getAll(): List<BillEntity> = billDao.getAll()

    suspend fun create(bill: BillEntity): Long = billDao.insert(bill)

    suspend fun update(bill: BillEntity) = billDao.update(bill)

    suspend fun delete(id: Long) = billDao.delete(id)
}