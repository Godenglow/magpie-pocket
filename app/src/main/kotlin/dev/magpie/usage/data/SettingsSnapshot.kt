package dev.magpie.usage.data

import kotlinx.coroutines.flow.first

/** Blocking-ish helper used by the worker and view models. */
suspend fun SettingsStore.settingsSnapshot(): SyncSettings = settings.first()
