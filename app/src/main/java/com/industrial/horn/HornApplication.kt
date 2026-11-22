package com.industrial.horn

import android.app.Application
import com.industrial.horn.di.AppContainer

class HornApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
