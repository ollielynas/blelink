package com.blelink.app

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Required by the Cast SDK (registered via AndroidManifest.xml's OPTIONS_PROVIDER_CLASS_NAME
 * meta-data) and by the chromecast-sender library on top of it.
 *
 * RECEIVER_APP_ID identifies our custom Cast receiver (docs/chromecast-receiver/ — a small web
 * page that runs YouTube's own IFrame Player API, the same technique youtube_player.html
 * already uses locally, just hosted for a TV instead of a WebView). It has to be registered on
 * the Google Cast SDK Developer Console pointing at that page's deployed URL
 * (https://ollielynas.com/blelink/chromecast-receiver/) before a real cast *session* can be
 * completed — that registration is a one-time step only the account holder can do.
 *
 * Until a real ID replaces this placeholder, device *discovery* still works fine (Chromecasts
 * on the network show up in the picker regardless of app ID — that's generic to the Cast
 * protocol), it's only actually launching a session that needs the registered ID.
 */
class CastOptionsProvider : OptionsProvider {

    companion object {
        // TODO: replace with the real registered receiver app ID once available.
        const val RECEIVER_APP_ID = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }

    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(RECEIVER_APP_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
