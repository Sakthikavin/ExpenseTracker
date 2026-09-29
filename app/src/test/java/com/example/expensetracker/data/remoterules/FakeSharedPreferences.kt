package com.example.expensetracker.data.remoterules

import android.content.SharedPreferences

/**
 * Enough of [SharedPreferences] for [RemoteRulesRepository] on the JVM: it only ever reads a cached
 * rule set and a timestamp, and writes both back.
 */
class FakeSharedPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {

    private val values = initial.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)

    override fun contains(key: String?) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { values.clear() }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            pending.clear()
        }
    }
}
