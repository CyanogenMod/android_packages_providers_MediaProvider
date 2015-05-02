/* //device/content/providers/media/src/com/android/providers/media/MediaScannerReceiver.java
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package com.android.providers.media;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.IOException;

public class MediaScannerReceiver extends BroadcastReceiver {
    private final static int BOOT_SCAN_ENABLE = 0;
    private final static int BOOT_SCAN_ASK = 1;
    private final static int BOOT_SCAN_DISABLE = 2;

    private final static int NOTIFICATION_ID = 1;

    // This delay prevents a scan on boot from mounting the sdcard
    private final static int DELAY_MS = 120000; // 2m

    private final static String TAG = "MediaScannerReceiver";

    private final static String SCAN_ALL = "com.android.providers.media.SCAN_ALL";
    private final static String DISMISS_SCAN = "com.android.providers.media.DISMISS_SCAN";

    private Handler mDelayScan = new Handler();

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        final Uri uri = intent.getData();

        // MediaScanner behavior on boot
        final int msob = Settings.System.getInt(context.getContentResolver(),
                Settings.System.MEDIA_SCANNER_ON_BOOT, 0);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            switch (msob) {
                case BOOT_SCAN_ENABLE:
                    // Scan both internal and external storage

                    scan(context, MediaProvider.INTERNAL_VOLUME);
                    scan(context, MediaProvider.EXTERNAL_VOLUME);
                    break;
                case BOOT_SCAN_ASK:
                    askToScan(context, true);
                    break;
                case BOOT_SCAN_DISABLE:
                    askToScan(context, false);
                    removeAsk(context, DELAY_MS);
                    break;
            }
        } else if (SCAN_ALL.equals(action)) {
            removeAsk(context, DELAY_MS);

            scan(context, MediaProvider.INTERNAL_VOLUME);
            scan(context, MediaProvider.EXTERNAL_VOLUME);
        } else if (DISMISS_SCAN.equals(action)) {
            removeAsk(context, DELAY_MS);
        } else {
            if (uri.getScheme().equals("file")) {
                if (!checkAsk(context)) {
                    // handle intents related to external storage
                    String path = uri.getPath();
                    String externalStoragePath = Environment.getExternalStorageDirectory().getPath();
                    String legacyPath = Environment.getLegacyExternalStorageDirectory().getPath();

                    try {
                        path = new File(path).getCanonicalPath();
                    } catch (IOException e) {
                        Log.e(TAG, "couldn't canonicalize " + path);
                        return;
                    }
                    if (path.startsWith(legacyPath)) {
                        path = externalStoragePath + path.substring(legacyPath.length());
                    }

                    Log.d(TAG, "action: " + action + " path: " + path);
                    if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                        // scan whenever any volume is mounted
                        scan(context, MediaProvider.EXTERNAL_VOLUME);
                    } else if (Intent.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(action) &&
                            path != null && path.startsWith(externalStoragePath + "/")) {
                        scanFile(context, path);
                    }
                }
            }
        }
    }

    private boolean checkAsk(Context context) {
        final Intent intent = new Intent(SCAN_ALL);
        boolean askExists = (PendingIntent.getBroadcast(context,
                1, intent, PendingIntent.FLAG_NO_CREATE) != null);
        return askExists;
    }

    private void removeAsk(final Context context, final int delay) {
        mDelayScan.postDelayed(new Runnable() {
            @Override
            public void run() {
                final Intent intent = new Intent(SCAN_ALL);
                PendingIntent.getBroadcast(context, 1, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT).cancel();
            }
        }, delay);
    }

    public void askToScan(Context context, boolean startNotify) {

        Intent intent = new Intent(SCAN_ALL);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                1, intent, 0);

        if (!startNotify) {
            return;
        }

        Notification.Builder mBuilder = new Notification.Builder(context)
            .setContentTitle(context.getString(R.string.ask_scan_title))
            .setContentText(context.getString(R.string.ask_scan_text))
            .setSmallIcon(R.drawable.ask_scan);

        mBuilder.setContentIntent(pendingIntent);

        intent = new Intent(DISMISS_SCAN);
        pendingIntent = PendingIntent.getBroadcast(context,
                2, intent, 0);
        mBuilder.setDeleteIntent(pendingIntent);

        NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notif = mBuilder.build();
        notif.flags    |= Notification.FLAG_AUTO_CANCEL;
        notif.priority  = Notification.PRIORITY_HIGH;

        notificationManager.notify(NOTIFICATION_ID, notif);
    }

    private void scan(Context context, String volume) {
        Bundle args = new Bundle();
        args.putString("volume", volume);
        context.startService(
                new Intent(context, MediaScannerService.class).putExtras(args));
    }    

    private void scanFile(Context context, String path) {
        Bundle args = new Bundle();
        args.putString("filepath", path);
        context.startService(
                new Intent(context, MediaScannerService.class).putExtras(args));
    }    
}
