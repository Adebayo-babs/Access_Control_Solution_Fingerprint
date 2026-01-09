package com.example.access_control_solution.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(staff: ProfileEntity): Long

    @Update
    fun update(staff: ProfileEntity)

    @Delete
    fun delete(staff: ProfileEntity)

    @Query("SELECT * FROM staff_profiles ORDER BY name ASC")
    fun getAllProfile(): List<ProfileEntity>

    @Query("SELECT * FROM staff_profiles WHERE id = :id")
    fun getProfileById(id: Long): ProfileEntity?

    @Query("SELECT * FROM staff_profiles WHERE lagId = :lagId")
    fun getProfileByLagId(lagId: String): ProfileEntity?

    @Query("DELETE FROM staff_profiles")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM staff_profiles")
    fun getProfileCount(): Int
}