package com.industrial.horn.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile

/** Creates the process-wide DataStore-backed preferences repository outside the app module. */
object HornPreferencesRepositoryFactory {
    fun create(context: Context): HornPreferencesRepository {
        val appContext = context.applicationContext
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { appContext.preferencesDataStoreFile(DATASTORE_NAME) },
            )
        return DataStoreHornPreferencesRepository(dataStore)
    }

    private const val DATASTORE_NAME = "horn_preferences"
}
