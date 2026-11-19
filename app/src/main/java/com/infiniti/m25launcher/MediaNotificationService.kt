package com.infiniti.m25launcher

import android.service.notification.NotificationListenerService

/**
 * Lightweight NotificationListenerService anchor required by Android's MediaSessionManager
 * to read active Now-Playing metadata and playback state from Spotify and system players.
 */
class MediaNotificationService : NotificationListenerService()
