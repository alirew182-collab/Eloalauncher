package dev.eloa.launcher.feature.common

import android.content.Context
import androidx.annotation.StringRes

/**
 * String resources outside composition (ViewModels, click handlers). Initialised with a context
 * that carries the selected app language; updated whenever the Activity is (re)created.
 */
@android.annotation.SuppressLint("StaticFieldLeak") // Holds only an application-derived context, never an Activity.
object Strings {
    @Volatile private var ctx: Context? = null

    fun init(context: Context) { ctx = Locales.wrap(context.applicationContext) }

    fun get(@StringRes id: Int, vararg args: Any): String {
        val c = ctx ?: error("Strings.init() was not called")
        return c.getString(id, *args)
    }
}

fun str(@StringRes id: Int, vararg args: Any): String = Strings.get(id, *args)
