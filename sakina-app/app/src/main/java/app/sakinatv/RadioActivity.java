package app.sakinatv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A live radio shortcut: plays BuildConfig.STREAM while it is on screen, and stops on Back or Home.
 * The TV has no reliable audio player of its own (some are disabled by the cleanup), so it plays here.
 */
public class RadioActivity extends Activity {
    private MediaPlayer player;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // STREAM can hold several links separated by "|": the next one is tried each time the current one fails.
    private final String[] links = BuildConfig.STREAM.split("\\|");
    private int link = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B3D2E, 0xFF06251C, 0xFF02110D});
        root.setBackground(bg);

        TextView title = text(getString(R.string.app_name), 64, Color.WHITE, true);
        TextView place = text(BuildConfig.STREAM_PLACE, 34, 0xFFE8D9A8, false);
        status = text("جارِ الاتصال…", 28, 0xB3FFFFFF, false);
        TextView hint = text("اضغط رجوع للإيقاف", 22, 0x80FFFFFF, false);
        root.addView(title);
        root.addView(place);
        root.addView(status);
        root.addView(hint);
        ((LinearLayout.LayoutParams) status.getLayoutParams()).topMargin = 48;
        ((LinearLayout.LayoutParams) hint.getLayoutParams()).topMargin = 64;
        setContentView(root);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    @Override
    protected void onStart() {
        super.onStart();
        play();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        release();
        finish(); // Home or another app: stop the radio and do not come back to a silent screen.
    }

    private void play() {
        release();
        status.setText("جارِ الاتصال…");
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        player.setOnPreparedListener(mp -> {
            mp.start();
            status.setText("● بث مباشر");
        });
        // A live stream drops now and then; try again quietly instead of showing an error.
        player.setOnErrorListener((mp, what, extra) -> { retry(); return true; });
        player.setOnCompletionListener(mp -> retry());
        try {
            player.setDataSource(links[link]);
            player.prepareAsync();
        } catch (Exception e) {
            retry();
        }
    }

    private void retry() {
        status.setText("انقطع البث… بنحاول تاني");
        link = (link + 1) % links.length;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::play, 5000);
    }

    private void release() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }
}
