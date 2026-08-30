package ua.kiosk.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the kiosk after boot / after app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { ctx.startActivity(i); } catch (Throwable ignored) {}
    }
}
