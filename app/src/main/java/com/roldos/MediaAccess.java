package com.roldos;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose. Android only lets an app see other apps' media sessions (what Spotify or
 * YouTube is playing, and their play/pause controls) if it is an approved notification
 * listener. This service is that approval and nothing more: it reads no notifications.
 * Approved once over adb by build.sh (`cmd notification allow_listener`).
 */
public class MediaAccess extends NotificationListenerService { }
