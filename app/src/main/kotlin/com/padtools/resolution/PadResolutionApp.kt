package com.padtools.resolution

import android.app.Application
import com.padtools.resolution.games.GameWatchService

class PadResolutionApp : Application() {
  override fun onCreate() {
    super.onCreate()
    // Whatever brought the app back to life (its icon, the Quick Settings tile, the watcher's
    // own alarm), the game watcher should be running again if the user wants it.
    GameWatchService.startIfWanted(this)
  }
}
