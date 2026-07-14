package com.idos.pos.business

/**
 * Single-row business profile orchestration (design.md "Interfaces" —
 * `business-profile` spec). [save] always upserts the single row via
 * [BusinessProfileDao.upsert]'s `OnConflictStrategy.REPLACE`, so calling it
 * more than once never creates a second row.
 */
class BusinessProfileRepository(private val dao: BusinessProfileDao) {

    suspend fun exists(): Boolean = dao.find() != null

    suspend fun get(): BusinessProfileEntity? = dao.find()

    suspend fun save(name: String, address: String, phone: String) {
        dao.upsert(BusinessProfileEntity(name = name, address = address, phone = phone))
    }
}
