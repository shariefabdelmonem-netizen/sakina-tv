package app.sakinatv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Opens this shortcut's video in the YouTube TV app, then gets out of the way. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (BuildConfig.REMINDERS) {
            Reminder.schedule(this);
            Adhan.schedule(this);
            MessageServer.start(this);
        }
        if (!BuildConfig.STREAM.isEmpty()) {
            startActivity(new Intent(this, RadioActivity.class));
            finish();
            return;
        }
        Uri video = Uri.parse("https://www.youtube.com/watch?v=" + BuildConfig.VIDEO);
        Intent yt = new Intent(Intent.ACTION_VIEW, video).setPackage("com.google.android.youtube.tv");
        try {
            startActivity(yt);
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, video));
        }
        finish();
    }
}
