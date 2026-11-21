package com.industrial.autohorn

import android.app.Application
import com.industrial.autohorn.di.AppContainer

class AutoHornApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
