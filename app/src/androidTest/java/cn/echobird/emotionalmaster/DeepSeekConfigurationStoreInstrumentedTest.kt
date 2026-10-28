package cn.echobird.emotionalmaster

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.echobird.emotionalmaster.storage.DeepSeekConfiguration
import cn.echobird.emotionalmaster.storage.DeepSeekConfigurationStoreTestAccess
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepSeekConfigurationStoreInstrumentedTest {
    @Test
    fun androidKeystore配置可往返且SharedPreferences不含明文() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val store = DeepSeekConfigurationStoreTestAccess.create(
            context = context,
            preferencesName = TEST_PREFERENCES_NAME,
            keyAlias = TEST_KEY_ALIAS,
        )
        val configuration = DeepSeekConfiguration(
            modelId = "deepseek-v4-pro",
            apiKey = TEST_API_KEY,
        )

        try {
            store.save(configuration)

            assertEquals(configuration, store.load())
            assertEquals(configuration.modelId, store.configuredModelId())
            assertFalse(preferences.all.values.joinToString().contains(TEST_API_KEY))
            assertTrue(androidKeyStore().containsAlias(TEST_KEY_ALIAS))

            store.clear()

            assertNull(store.load())
            assertNull(store.configuredModelId())
            assertFalse(preferences.all.keys.any { it.contains("api", ignoreCase = true) })
            assertFalse(androidKeyStore().containsAlias(TEST_KEY_ALIAS))
        } finally {
            preferences.edit().clear().commit()
            androidKeyStore().run {
                if (containsAlias(TEST_KEY_ALIAS)) {
                    deleteEntry(TEST_KEY_ALIAS)
                }
            }
        }
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val TEST_PREFERENCES_NAME = "deepseek-configuration-instrumentation-test"
        const val TEST_KEY_ALIAS = "emotional-master-deepseek-instrumentation-test-key"
        const val TEST_API_KEY = "sk-test-log-secret"
    }
}
