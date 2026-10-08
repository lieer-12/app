package com.example.lifemanager.ui.navigation

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.Navigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real NavController and entry stores; only the visual destination renderer is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TopLevelNavigationTest {
    // Without saved-entry restoration, the second visit constructs a new owner and loses its draft.
    @Test fun twentyTabCyclesKeepOneOwnerPerModuleAndPreserveTheirDrafts() {
        val fixture = Fixture()
        val owners = mutableMapOf<String, DraftOwner>()
        try {
            repeat(20) {
                listOf("todo", "schedule", "habit", "subscription", "settings").forEach { route ->
                    fixture.controller.navigateTopLevel(route)
                    val entry = fixture.controller.getBackStackEntry(route)
                    val actual = ViewModelProvider(entry)[DraftOwner::class.java]
                    val first = owners[route]
                    if (first == null) {
                        actual.draft = "unsaved-$route"
                        owners[route] = actual
                    } else {
                        assertSame(first, actual, "$route must reuse its first owner, not multiply observers")
                        assertEquals("unsaved-$route", actual.draft)
                        assertFalse(actual.cleared)
                    }
                }
            }
            assertEquals(5, owners.size)
        } finally {
            fixture.store.clear()
        }
        owners.values.forEach { assertTrue(it.cleared, "The Activity store still owns final cleanup") }
    }

    // SingleTop alone preserves every different tab visit; one Back must instead reach Todo.
    @Test fun repeatedCyclesKeepOnlyRootAndCurrentTabInTheActiveBackStack() {
        val fixture = Fixture()
        try {
            repeat(20) {
                listOf("todo", "schedule", "habit", "subscription", "settings")
                    .forEach(fixture.controller::navigateTopLevel)
            }
            assertTrue(fixture.controller.popBackStack())
            assertEquals("todo", fixture.controller.currentDestination?.route)
            assertFalse(fixture.controller.popBackStack())
        } finally {
            fixture.store.clear()
        }
    }

    class DraftOwner : ViewModel() {
        var draft = ""
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private class Fixture {
        val store = ViewModelStore()
        private val owner = object : LifecycleOwner {
            private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        val controller = NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
            setLifecycleOwner(owner)
            setViewModelStore(store)
            val renderer = ImmediateNavigator()
            navigatorProvider.addNavigator(renderer)
            graph = createGraph(startDestination = "todo") {
                listOf("todo", "schedule", "habit", "subscription", "settings").forEach { route ->
                    addDestination(renderer.createDestination().apply { this.route = route })
                }
            }
        }
    }

    @Navigator.Name("immediate")
    private class ImmediateNavigator : Navigator<NavDestination>() {
        override fun createDestination() = NavDestination(this)
    }
}
