package com.example.access_control_solution.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "staff_profiles",
    indices = [Index(value = ["lagId"], unique = true)]
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val lagId: String,
    val faceTemplate: ByteArray,
    val faceImage: ByteArray,
    val thumbnail: ByteArray? = null,
    val timestamp: Long = System.currentTimeMillis()

) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProfileEntity

        if (id != other.id) return false
        if (name != other.name) return false
        if (lagId != other.lagId) return false
        if (!faceTemplate.contentEquals(other.faceTemplate)) return false
        if (!faceImage.contentEquals(other.faceImage)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + lagId.hashCode()
        result = 31 * result + faceTemplate.contentHashCode()
        result = 31 * result + faceImage.contentHashCode()
        return result
    }

}