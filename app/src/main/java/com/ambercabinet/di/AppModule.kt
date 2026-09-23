package com.ambercabinet.di

import android.content.Context
import androidx.room.Room
import com.ambercabinet.core.data.db.AppDatabase
import com.ambercabinet.core.data.seed.SeedCatalog
import com.ambercabinet.core.domain.InventoryService
import com.ambercabinet.core.domain.MatchEngine
import com.ambercabinet.core.data.repo.CabinetRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "amber-cabinet.db")
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)   /* 明确迁移，禁止破坏性兜底（§三.12） */
            .build()

    /** 初始配方数据：版本化 JSON，加载即校验（§10.3），失败抛错不进入正式数据集 */
    @Provides
    @Singleton
    fun provideCatalog(@ApplicationContext context: Context): SeedCatalog = SeedCatalog.load(context)

    @Provides
    @Singleton
    fun provideMatchEngine(repo: CabinetRepository, catalog: SeedCatalog): MatchEngine =
        MatchEngine(repo, catalog.substitutions)

    @Provides
    @Singleton
    fun provideInventoryService(repo: CabinetRepository, matchEngine: MatchEngine): InventoryService =
        InventoryService(repo, repo, matchEngine)
}
