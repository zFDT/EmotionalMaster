package cn.echobird.emotionalmaster.storage

import android.content.Context

/**
 * 仅供 Debug instrumentation 使用，为测试隔离 SharedPreferences 与 Keystore alias。
 */
object DeepSeekConfigurationStoreTestAccess {
    fun create(
        context: Context,
        preferencesName: String,
        keyAlias: String,
    ): DeepSeekConfigurationStore =
        SharedPreferencesDeepSeekConfigurationStore(context, preferencesName, keyAlias)
}
