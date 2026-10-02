package com.example.lifemanager.di

import com.example.lifemanager.data.repository.TodoRepositoryImpl
import com.example.lifemanager.data.repository.ScheduleRepositoryImpl
import com.example.lifemanager.data.repository.HabitRepositoryImpl
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.repository.HabitRepository
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.notification.ReminderScheduler
import com.example.lifemanager.notification.ReminderSchedulerContract
import com.example.lifemanager.notification.ScheduleReminderScheduler
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import com.example.lifemanager.notification.SubscriptionReminderScheduler
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindTodoRepository(impl: TodoRepositoryImpl): TodoRepository

    @Binds
    @Singleton
    abstract fun bindScheduleRepository(impl: ScheduleRepositoryImpl): ScheduleRepository

    @Binds
    @Singleton
    abstract fun bindHabitRepository(impl: HabitRepositoryImpl): HabitRepository

    @Binds
    @Singleton
    abstract fun bindSubscriptionRepository(impl: SubscriptionRepositoryImpl): SubscriptionRepository

}

@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {
    @Provides
    @Singleton
    fun provideSubscriptionReminderScheduler(@ApplicationContext context: Context): SubscriptionReminderSchedulerContract =
        SubscriptionReminderScheduler(context)

    @Provides
    @Singleton
    fun provideReminderScheduler(@ApplicationContext context: Context): ReminderSchedulerContract =
        ReminderScheduler(context)

    @Provides
    @Singleton
    fun provideScheduleReminderScheduler(@ApplicationContext context: Context): ScheduleReminderSchedulerContract =
        ScheduleReminderScheduler(context)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
