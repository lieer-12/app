package com.example.lifemanager.ui.navigation

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.ui.common.testGenerationAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleNavigationViewModelTest {
    private lateinit var model: ScheduleNavigationViewModel

    @Before fun setup() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        model = ScheduleNavigationViewModel(testGenerationAccess(), dispatcher)
    }

    @After fun cleanup() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun consumedRequestDoesNotReplay() {
        model.open(42)
        val request = assertNotNull(model.pending.value)
        assertEquals(42L, request.scheduleId)
        model.consume(request.token)
        assertNull(model.pending.value)
    }
    @Test fun repeatedTapCannotBeConsumedByOldAcknowledgement() {
        model.open(42)
        val first = assertNotNull(model.pending.value)
        model.open(42)
        val second = assertNotNull(model.pending.value)
        assertNotEquals(first.token, second.token)
        model.consume(first.token)
        assertEquals(second, model.pending.value)
    }
    @Test fun invalidIdDoesNotOverwriteRealRequest() {
        model.open(42)
        val original = model.pending.value
        model.open(0); model.open(-1)
        assertEquals(original, model.pending.value)
    }
}
