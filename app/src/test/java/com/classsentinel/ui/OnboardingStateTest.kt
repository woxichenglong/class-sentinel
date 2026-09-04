package com.classsentinel.ui

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.classsentinel.data.InMemorySecretStore
import com.classsentinel.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Task 33：首次启动路由和完成标记的最小可验证契约。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingStateTest {

    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        file = File.createTempFile("onboarding-test", ".preferences_pb")
        file.deleteOnExit()
        dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { file },
        )
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `first run starts onboarding and completed run starts home`() {
        assertEquals(ONBOARDING_ROUTE, startDestinationForOnboarding(completed = false))
        assertEquals(HOME_ROUTE, startDestinationForOnboarding(completed = true))
        assertFalse(isBottomBarRoute(ONBOARDING_ROUTE))
        assertTrue(isBottomBarRoute(HOME_ROUTE))
    }

    @Test
    fun `onboarding completion persists across repository recreation`() = runBlocking {
        val first = SettingsRepository(
            dataStore = dataStore,
            secretStore = InMemorySecretStore(),
            syncEnabled = false,
        )
        first.load()
        assertFalse(first.onboardingCompletedFlow.first())

        first.saveOnboardingCompleted()
        assertTrue(first.onboardingCompletedFlow.first())

        val second = SettingsRepository(
            dataStore = dataStore,
            secretStore = InMemorySecretStore(),
            syncEnabled = false,
        )
        assertTrue(second.onboardingCompletedFlow.first())
    }
}
