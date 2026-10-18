package com.yiyu.ledger.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yiyu.ledger.domain.*

@Entity(tableName = "pending")
data class PendingRecording(@PrimaryKey val id: String, val audioName: String, val referenceDate: String,
    val createdAt: Long, val resultJson: String, val savedCount: Int, val resultOffset: Int, val source: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM entries ORDER BY occurredOn DESC, createdAt DESC") suspend fun entries(): List<LedgerEntry>
    @Query("SELECT * FROM entries WHERE id=:id") suspend fun entry(id: String): LedgerEntry?
    @Query("SELECT * FROM categories ORDER BY rowid") suspend fun categories(): List<CustomCategory>
    @Query("SELECT * FROM pending ORDER BY createdAt") suspend fun pending(): List<PendingRecording>
    @Query("SELECT * FROM pending WHERE id=:id") suspend fun pending(id: String): PendingRecording?
    @Query("SELECT * FROM monthly_budgets ORDER BY month") suspend fun monthlyBudgets(): List<MonthlyBudget>
    @Upsert suspend fun putBudget(budget: MonthlyBudget)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entry: LedgerEntry)
    @Update suspend fun update(entry: LedgerEntry): Int
    @Query("DELETE FROM entries WHERE id=:id") suspend fun deleteEntry(id: String)
    @Query("UPDATE entries SET linkedEntryId='', updatedAt=:now WHERE linkedEntryId=:id") suspend fun unlink(id: String, now: Long)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun addCategory(category: CustomCategory)
    @Query("DELETE FROM categories WHERE id=:id") suspend fun deleteCategory(id: String)
    @Upsert suspend fun putPending(recording: PendingRecording)
    @Query("DELETE FROM pending WHERE id=:id") suspend fun deletePending(id: String)
}

@Database(entities = [LedgerEntry::class, CustomCategory::class, PendingRecording::class, MonthlyBudget::class], version = 2, exportSchema = true)
abstract class LedgerDatabase: RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        val MIGRATION_1_2 = object: Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `monthly_budgets` (`month` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, PRIMARY KEY(`month`))")
            }
        }
        fun open(context: Context, name: String = "yiyu-ledger.db") = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2)
            .addCallback(object: Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("PRAGMA secure_delete=ON").use { while (it.moveToNext()) Unit }
                }
            }).build()
    }
}
