package de.ostlertekker.ultraiptv.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.ostlertekker.ultraiptv.core.database.ContentDao
import de.ostlertekker.ultraiptv.core.database.EpgDao
import de.ostlertekker.ultraiptv.core.database.ExtrasDao
import de.ostlertekker.ultraiptv.core.database.Migrations
import de.ostlertekker.ultraiptv.core.database.UltraDatabase
import de.ostlertekker.ultraiptv.core.database.UserDao
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

const val USER_AGENT = "UltraIPTV/1.0 (Android TV; OSTLERTEKKER)"

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): UltraDatabase =
        Room.databaseBuilder(context, UltraDatabase::class.java, "ultra.db")
            .addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                // Beim Öffnen übergroße Anbieter-Einträge kürzen (verhindert Abstürze in Filme/Serien)
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Einmal pro App-Version (neue Daten werden direkt nach jedem Sync bereinigt)
                    val prefs = context.getSharedPreferences("db_maintenance", Context.MODE_PRIVATE)
                    if (prefs.getInt("sanitized", 0) == de.ostlertekker.ultraiptv.BuildConfig.VERSION_CODE) return
                    Migrations.SANITIZE.forEach { runCatching { db.execSQL(it) } }
                    prefs.edit().putInt("sanitized", de.ostlertekker.ultraiptv.BuildConfig.VERSION_CODE).apply()
                }
            })
            .build()

    @Provides fun contentDao(db: UltraDatabase): ContentDao = db.content()
    @Provides fun epgDao(db: UltraDatabase): EpgDao = db.epg()
    @Provides fun userDao(db: UltraDatabase): UserDao = db.user()
    @Provides fun extrasDao(db: UltraDatabase): ExtrasDao = db.extras()
    @Provides fun discoverDao(db: UltraDatabase): de.ostlertekker.ultraiptv.core.database.DiscoverDao = db.discover()

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()
}
