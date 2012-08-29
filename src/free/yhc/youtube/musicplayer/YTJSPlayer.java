package free.yhc.youtube.musicplayer;

import static free.yhc.youtube.musicplayer.model.Utils.eAssert;
import static free.yhc.youtube.musicplayer.model.Utils.logD;
import static free.yhc.youtube.musicplayer.model.Utils.logI;
import static free.yhc.youtube.musicplayer.model.Utils.logW;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import free.yhc.youtube.musicplayer.model.DB;
import free.yhc.youtube.musicplayer.model.DB.ColVideo;
import free.yhc.youtube.musicplayer.model.Err;
import free.yhc.youtube.musicplayer.model.NanoHTTPD;
import free.yhc.youtube.musicplayer.model.Policy;
import free.yhc.youtube.musicplayer.model.Utils;

// Player State Diagram
// * [Playing] :  ajsStop()
//      [Playing] -> [Unstarted] -> [Video Cued]
//
// * [Unstarted] : PlayVideo()
//      [Unstarted] -> [Video Cued] -> [Buffering] -> [Playing]
//
// See Youtube player Javascript API document
public class YTJSPlayer {
    private static final String WLTAG               = "YTJSPlayer";
    private static final int    WEBSERVER_PORT      = Policy.Constants.WEBSERVER_PORT;
    private static final String YTPLAYER_SCRIPT     = "ytplayer.html";

    private static final int YTPSTATE_UNSTARTED     = -1;
    private static final int YTPSTATE_ENDED         = 0;
    private static final int YTPSTATE_PLAYING       = 1;
    private static final int YTPSTATE_PAUSED        = 2;
    private static final int YTPSTATE_BUFFERING     = 3;
    private static final int YTPSTATE_VIDEO_CUED    = 5;
    private static final int YTPSTATE_ERROR         = -10;
    private static final int YTPSTATE_INVALID       = -100;

    private static final int YTPERRCODE_OK              = 0;
    private static final int YTPERRCODE_INVALID_PARAM   = 2;
    private static final int YTPERRCODE_NOT_FOUND       = 100;
    private static final int YTPERRCODE_NOT_ALLOWED     = 101;
    private static final int YTPERRCODE_NOT_ALLOWED2    = 150;


    private static final Comparator<NrElem> sNrElemComparator = new Comparator<NrElem>() {
            @Override
            public int compare(NrElem o1, NrElem o2) {
                if (o1.n > o2.n)
                    return 1;
                else if (o1.n < o2.n)
                    return -1;
                else
                    return 0;
            }
        };

    private static final Comparator<Video> sVideoTitleComparator = new Comparator<Video>() {
        @Override
        public int compare(Video o1, Video o2) {
            return o1.title.compareTo(o2.title);
        }
    };

    private static YTJSPlayer sInstance = null;


    private final Resources     mRes        = Utils.getAppContext().getResources();
    private final DB            mDb         = DB.get();

    // ------------------------------------------------------------------------
    // Final Runnables
    // ------------------------------------------------------------------------
    private final UpdateProgress        mUpdateProg = new UpdateProgress();

    // ------------------------------------------------------------------------
    //
    // ------------------------------------------------------------------------
    private WakeLock            mWl         = null;
    private WifiLock            mWfl        = null;

    private WebView             mWv         = null; // WebView instance.
    private int                 mYtpS       = YTPSTATE_UNSTARTED; // state of YTP;
    private int                 mYtpEC      = YTPERRCODE_OK;

    // ------------------------------------------------------------------------
    // UI Control.
    // ------------------------------------------------------------------------
    private Context             mVContext   = null;
    private LinearLayout        mPlayerv    = null;

    // ------------------------------------------------------------------------
    // Player Runtime Status
    // ------------------------------------------------------------------------
    private OnPlayerReadyListener mPlayerReadyListener = null;
    private Video[]               mVideos = null;
    private int                   mVideoi = -1;

    public static class Video {
        String   title;
        String   videoId;
        int      volume;
        public Video(String aVideoId, String aTitle, int aVolume) {
            videoId = aVideoId;
            title = aTitle;
            volume = aVolume;
        }
    }

    private static class NrElem {
        public int      n;
        public Object   tag;
        NrElem(int aN, Object aTag) {
            n = aN;
            tag = aTag;
        }
    }

    public interface OnPlayerReadyListener {
        void onPlayerReady(WebView wv);
    }

    private class UpdateProgress {
        private ProgressBar progbar = null;
        private int         lastProgress = -1;

        void setProgbar(ProgressBar aProgbar) {
            eAssert(Utils.isUiThread());
            progbar = aProgbar;
            if (null != progbar)
                progbar.setProgress(lastProgress);
        }

        void start(ProgressBar aProgbar) {
            logI("Progress Start");
            progbar = aProgbar;
            lastProgress = -1;
        }

        void end() {
            logI("Progress End");
            progbar = null;
            lastProgress = -1;
        }

        void update(int duration, int currentPos) {
            logI("Progress Update(" + (null == progbar? "X": "O") + ") : " + currentPos + " / " + duration);
            if (null != progbar) {
                int curProgress = (duration > 0)? currentPos * 100 / duration
                                                : 0;
                if (curProgress > lastProgress)
                    progbar.setProgress(curProgress);

                lastProgress = curProgress;
            }
        }
    }

    private class WVClient extends WebViewClient {
        @Override
        public boolean
        shouldOverrideUrlLoading(WebView wView, String url) {
            logI("WebView : shouldOverrideUrlLoading : " + url);
            return true;
        }

        @Override
        public void
        onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            logI("WebView : onPageStarted : " + url);
        }

        @Override
        public void
        onLoadResource(WebView view, String url) {
            super.onLoadResource(view, url);
            logI("WebView : onLoadResource : " + url);
            /*
            if (url.startsWith("http://s.youtube.com/s")) {
                logI("WebView : onLoadResource(Youtube contents?) : " + url);
            }
            */
        }

        @Override
        public void
        onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            logI("WebView : onPageFinished : " + url);
        }

        @Override
        public void
        onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            logI("WebView : URL : " + failingUrl + "\nOh no! " + description);
        }

        @Override
        public void
        onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            super.onReceivedSslError(view, handler, error);
            logI("WebView : SSL Error : " + error.toString());
        }
    }

    private class WCClient extends WebChromeClient {
        @Override
        public View
        getVideoLoadingProgressView() {
            logI("WebView : getVideoLoadingProgressView");
            return super.getVideoLoadingProgressView();
        }

        @Override
        public void
        onShowCustomView(View view, CustomViewCallback callback) {
            super.onShowCustomView(view, callback);
            logI("WebView : onShowCustomView");
        }

        @Override
        public void
        onHideCustomView() {
            super.onHideCustomView();
            logI("WebView : onHideCustomView");
        }

        @Override
        public boolean
        onJsAlert(WebView view, String url, String message, JsResult result) {
            logI("JSMsg : " + message);
            return true;
        }
    }

    private void
    setWebSettings(WebView wv) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        // PluginState.ON should be set!
        ws.setPluginState(WebSettings.PluginState.ON);
        ws.setLoadsImagesAutomatically(false);
    }


    // ========================================================================
    //
    // Debug
    //
    // ========================================================================
    private String
    dbgStateName(int state) {
        switch (state) {
        case YTPSTATE_UNSTARTED:
            return "unstarted";

        case YTPSTATE_ENDED:
            return "ended";

        case YTPSTATE_PLAYING:
            return "playing";

        case YTPSTATE_PAUSED:
            return "paused";

        case YTPSTATE_BUFFERING:
            return "buffering";

        case YTPSTATE_VIDEO_CUED:
            return "video queued";

        case YTPSTATE_ERROR:
            return "error";

        default:
            eAssert(false);
        }
        return null;
    }

    // ========================================================================
    //
    //
    //
    // ========================================================================
    private void
    acquireLocks() {
        eAssert(null == mWl && null == mWfl);
        mWl = ((PowerManager)Utils.getAppContext().getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WLTAG);
        // Playing youtube requires high performance wifi for high quality media play.
        mWfl = ((WifiManager)Utils.getAppContext().getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WLTAG);
        mWl.acquire();
        mWfl.acquire();
    }

    private void
    releaseLocks() {
        if (null != mWl)
                mWl.release();

        if (null != mWfl)
            mWfl.release();

        mWl = null;
        mWfl = null;
    }

    // ========================================================================
    //
    //
    //
    // ========================================================================
    private void
    ytpSetState(int state) {
        if (state == mYtpS)
            return;

        int old = mYtpS;
        mYtpS = state;
        onYtpStateChanged(old, state);
    }

    private int
    ytpGetState() {
        return mYtpS;
    }
    // ========================================================================
    //
    // General Control
    //
    // ========================================================================
    private void
    playVideo(String videoId, int volume) {
        eAssert(0 <= volume && volume <= 100);
        ajsPrepare(videoId);
        ajsSetVolume(volume);
        ajsPlay();
        // Below may fail if video is deleted while playlist of videos are playing.
        // But, this case can be ignored.
        mDb.updateVideo(ColVideo.VIDEOID, videoId,
                        ColVideo.TIME_PLAYED, System.currentTimeMillis());
    }

    private void
    playNext() {
        if (null == mVideos)
            return; // do nothing

        mVideoi++;
        if (mVideoi >= mVideos.length) {
            mVideoi = mVideos.length;
            ytpPlayDone();
        } else
            playVideo(mVideos[mVideoi].videoId, mVideos[mVideoi].volume);
    }

    private void
    playPrev() {
        if (null == mVideos)
            return; // do nothing

        mVideoi--;
        if (mVideoi < 0) {
            mVideoi = -1;
            ytpPlayDone();
        } else
            playVideo(mVideos[mVideoi].videoId, mVideos[mVideoi].volume);
    }

    private void
    onYtpStateChanged(int from, int to) {
        logI("onYtpStateChanged : " + dbgStateName(from) + " -> " + dbgStateName(to));
        configurePlayerViewAll(mPlayerv, from, to);

        switch (to) {
        case YTPSTATE_UNSTARTED:
            break;

        case YTPSTATE_ENDED:
            playNext();
            break;

        case YTPSTATE_PLAYING:
            break;

        case YTPSTATE_PAUSED:
            break;

        case YTPSTATE_BUFFERING:
            break;

        case YTPSTATE_VIDEO_CUED:
            break;

        default:
            eAssert(false);
        }
    }

    private void
    ytpPlayDone() {
        logD("VideoView - playDone");
        if (null != mPlayerv) {
            setPlayerViewTitle((TextView)mPlayerv.findViewById(R.id.music_player_title),
                                mRes.getText(R.string.msg_playing_done), false);
        }
        releaseLocks();
    }

    // ========================================================================
    //
    // Player View Handling
    //
    // ========================================================================
    private void
    disableButton(ImageView btn) {
        btn.setClickable(false);
        btn.setImageResource(R.drawable.ic_block);
    }

    private void
    enableButton(ImageView btn, int image) {
        btn.setClickable(true);
        btn.setImageResource(image);
    }

    private void
    setPlayerViewTitle(TextView titlev, CharSequence title, boolean buffering) {
        if (null == titlev || null == title)
            return;

        if (buffering)
            titlev.setText("(" + mRes.getText(R.string.buffering) + ") " + title);
        else
            titlev.setText(title);
    }

    private void
    configurePlayerViewTitle(TextView titlev, int from, int to) {
        if (null == titlev)
            return;

        CharSequence videoTitle = "";
        if (null != mVideos
            && 0 <= mVideoi
            && mVideoi < mVideos.length) {
            videoTitle = mVideos[mVideoi].title;
        }

        switch (to) {
        case YTPSTATE_BUFFERING: {
            eAssert(null != videoTitle);
            setPlayerViewTitle(titlev, videoTitle, true);
        } break;

        case YTPSTATE_PAUSED:
        case YTPSTATE_PLAYING:
            eAssert(null != videoTitle);
            if (null != videoTitle)
                setPlayerViewTitle(titlev, videoTitle, false);
            break;

        case YTPSTATE_ERROR:
            setPlayerViewTitle(titlev, mRes.getText(R.string.msg_ytplayer_err), false);
            break;

        default:
            setPlayerViewTitle(titlev, mRes.getText(R.string.msg_preparing_mplayer), false);
        }
    }


    private void
    disablePlayerViewControlButton(ViewGroup playerv) {
        disableButton((ImageView)playerv.findViewById(R.id.music_player_btnplay));
        disableButton((ImageView)playerv.findViewById(R.id.music_player_btnnext));
        disableButton((ImageView)playerv.findViewById(R.id.music_player_btnprev));
    }

    private void
    configurePlayerViewControl(ViewGroup controlv, int from, int to) {
        if (null == controlv)
            return;

        if (null == mVideos) {
            controlv.setVisibility(View.GONE);
            return;
        }

        controlv.setVisibility(View.VISIBLE);
        switch (to) {
        case YTPSTATE_BUFFERING:
            if (YTPSTATE_VIDEO_CUED != from)
                break; // if this is NOT first buffering, do nothing.
        case YTPSTATE_PAUSED:
        case YTPSTATE_PLAYING:
            if (mVideos.length - 1 <= mVideoi)
                disableButton((ImageView)controlv.findViewById(R.id.music_player_btnnext));
            else
                enableButton((ImageView)controlv.findViewById(R.id.music_player_btnnext),
                             R.drawable.ic_media_next);

            if (0 >= mVideoi)
                disableButton((ImageView)controlv.findViewById(R.id.music_player_btnprev));
            else
                enableButton((ImageView)controlv.findViewById(R.id.music_player_btnprev),
                             R.drawable.ic_media_prev);
            break;
        }

        switch (to) {
        case YTPSTATE_PAUSED: {
            ImageView btn = (ImageView)controlv.findViewById(R.id.music_player_btnplay);
            btn.setClickable(true);
            btn.setImageResource(R.drawable.ic_media_play);
        } break;

        case YTPSTATE_PLAYING: {
            ImageView btn = (ImageView)controlv.findViewById(R.id.music_player_btnplay);
            btn.setClickable(true);
            btn.setImageResource(R.drawable.ic_media_pause);
        } break;

        case YTPSTATE_BUFFERING:
            ; // ignore when buffering
            break;

        default:
            controlv.setVisibility(View.GONE);
        }
    }

    private void
    configurePlayerViewProgressBar(ProgressBar pbar, int from, int to) {

        if (null == pbar)
            return;

        switch (to) {
        case YTPSTATE_VIDEO_CUED:
            mUpdateProg.start(pbar);
            break;

        case YTPSTATE_ENDED:
            // Workaround of Youtube player.
            // Sometimes Youtube player doesn't update progress 100% before playing is ended.
            // So, update to 100% in force at this ended state.
            mUpdateProg.update(1, 1);
            // Missing 'break' is intentional.
        case YTPSTATE_UNSTARTED:
            mUpdateProg.end();
            break;

        case YTPSTATE_PLAYING:
        case YTPSTATE_PAUSED:
        case YTPSTATE_BUFFERING:
            ; // do nothing progress is now under update..
            break;

        default:
            mUpdateProg.end();
            pbar.setProgress(0);
        }
    }

    private void
    configurePlayerViewAll(ViewGroup playerv, int from, int to) {
        if (null == playerv)
            return; // nothing to do

        if ((YTPSTATE_UNSTARTED == to || YTPSTATE_VIDEO_CUED == to)
            && (null == mVideos
                || 0 > mVideoi
                || mVideoi >= mVideos.length))
            mPlayerv.setVisibility(View.GONE);
        else
            mPlayerv.setVisibility(View.VISIBLE);

        configurePlayerViewTitle((TextView)playerv.findViewById(R.id.music_player_title),
                                 from, to);
        configurePlayerViewProgressBar((ProgressBar)playerv.findViewById(R.id.music_player_progressbar),
                                       from, to);
        configurePlayerViewControl((ViewGroup)playerv.findViewById(R.id.music_player_control),
                                   from, to);
    }

    private void
    setupPlayerViewControlButton(final ViewGroup playerv) {
        ImageView btn = (ImageView)playerv.findViewById(R.id.music_player_btnplay);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch (ytpGetState()) {
                case YTPSTATE_PAUSED:
                    ajsPlay();
                    break;

                case YTPSTATE_PLAYING:
                    ajsPause();
                    // prevent clickable during transition player state.
                    break;

                default:
                    ; // do nothing.
                }
                disablePlayerViewControlButton(playerv);
            }
        });

        btn = (ImageView)playerv.findViewById(R.id.music_player_btnprev);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ajsStop();
                playPrev();
                disablePlayerViewControlButton(playerv);
            }
        });

        btn = (ImageView)playerv.findViewById(R.id.music_player_btnnext);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ajsStop();
                playNext();
                disablePlayerViewControlButton(playerv);
            }
        });
    }

    private void
    initPlayerView(ViewGroup playerv) {
        mUpdateProg.setProgbar((ProgressBar)playerv.findViewById(R.id.music_player_progressbar));
        setupPlayerViewControlButton(playerv);
        configurePlayerViewAll(playerv, YTPSTATE_INVALID, ytpGetState());
    }

    // ------------------------------------------------------------------------
    // Listeners run on UI thread
    // ------------------------------------------------------------------------
    private void
    onPlayerReady() {
        if (null != mPlayerReadyListener)
            mPlayerReadyListener.onPlayerReady(mWv);
    }

    private void
    onPlayerStateChanged(int state) {
        ytpSetState(state);
    }

    private void
    onPlayerError(int errCode) {
        ytpSetState(YTPSTATE_ERROR);
        if (null != mVideos
            && mVideoi >= 0
            && mVideoi < mVideos.length - 1) {
            ajsStop();
            playNext();
        }
    }

    private void
    onNotifyPlayerInfo(int videoDuration, int videoCurrentTime) {
        mUpdateProg.update(videoDuration, videoCurrentTime);
    }

    // ========================================================================
    //
    // Java script player interface
    //
    // ========================================================================
    private void
    callJsFunction(String func, String... args) {
        eAssert(null != mWv);

        if (null == mWv)
            return;

        StringBuilder bldr = new StringBuilder("javascript:")
                .append(func)
                .append("(");
        int i = 0;
        for (i = 0; i < args.length - 1; i++)
            bldr.append("\"")
                .append(args[i])
                .append("\", ");

        if (args.length > 0)
            bldr.append("\"")
                .append(args[i])
                .append("\"");

        bldr.append(")");
        mWv.loadUrl(bldr.toString());
    }

    // NOTATION
    // jsa : JavaScript to Android
    // ajs : Android to JavaScript
    public void
    jsaLog(String msg) {
        logI(msg);
    }

    public void
    jsaOnPlayerReady() {
        Utils.getUiHandler().post(new Runnable() {
            @Override
            public void run() {
                onPlayerReady();
            }
        });
    }

    public void
    jsaOnPlayerStateChanged(final int state) {
        logI("OnPlayerStateChanged : " + state);
        Utils.getUiHandler().post(new Runnable() {
            @Override
            public void run() {
                onPlayerStateChanged(state);
            }
        });
    }

    public void
    jsaOnPlayerError(final int errCode) {
        logI("OnPlayerError : " + errCode);
        Utils.getUiHandler().post(new Runnable() {
            @Override
            public void run() {
                onPlayerError(errCode);
            }
        });
    }

    public void
    jsaOnNotifyPlayerInfo(final int videoDuration,
                          final int videoCurrentTime) {
        Utils.getUiHandler().post(new Runnable() {
            @Override
            public void run() {
                onNotifyPlayerInfo(videoDuration, videoCurrentTime);
            }
        });
    }

    private void
    ajsPrepare(String videoId) {
        eAssert(null != mWv && null != mVContext);
        callJsFunction("prepareVideo", videoId);
    }

    private void
    ajsPlay() {
        callJsFunction("playVideo");
    }

    private void
    ajsPause() {
        callJsFunction("pauseVideo");
    }

    /**
     * State of YoutubePlayer will move to
     *   [ current ] -> 'unstarted' -> 'video cued'.
     */
    private void
    ajsStop() {
        callJsFunction("stopVideo");
    }

    private void
    ajsSetVolume(int volume) {
        callJsFunction("setVideoVolume", "" + volume);
    }

    // ========================================================================
    //
    // Public interface
    //
    // ========================================================================
    private YTJSPlayer() {
    }

    public static YTJSPlayer
    get() {
        if (null == sInstance)
            sInstance = new YTJSPlayer();
        return sInstance;
    }

    @Override
    protected void
    finalize() {
    }

    public Err
    init() {
        /* TODO
        File fScript = new File(Utils.getAppContext().getFilesDir().getAbsolutePath()
                                + "/" + YTPLAYER_SCRIPT);
        if (!fScript.exists())
            Utils.copyAssetFile(YTPLAYER_SCRIPT);
        */
        new File(Utils.getAppContext().getFilesDir().getAbsolutePath() + "/" + YTPLAYER_SCRIPT).delete();
        Utils.copyAssetFile(YTPLAYER_SCRIPT);

        // NOTE
        // script for chromeless player should be loaded from webserver
        // (See youtube documentation for details.)
        // Start simple webserver
        try {
            new NanoHTTPD(WEBSERVER_PORT, Utils.getAppContext().getFilesDir());
        } catch (IOException e) {
            logI("Fail to start Nanohttpd");
        }
        return Err.NO_ERR;
    }

    public Err
    prepare(WebView wv, OnPlayerReadyListener listener) {
        logI("YTJSPlayer : Prepare!!");
        mWv = wv;
        mPlayerReadyListener = listener;
        wv.setWebViewClient(new WVClient());
        wv.setWebChromeClient(new WCClient());
        setWebSettings(wv);
        mWv.addJavascriptInterface(this, "Android");
        mWv.loadUrl("http://127.0.0.1:" + WEBSERVER_PORT + "/" + YTPLAYER_SCRIPT);

        return Err.NO_ERR;
    }

    public Err
    setController(Context context, ViewGroup playerv) {
        if (context == mVContext && mPlayerv == playerv)
            // controller is already set for this context.
            // So, nothing to do. just return!
            return Err.NO_ERR;

        mVContext = context;
        mPlayerv = (LinearLayout)playerv;

        if (null == mPlayerv)
            return Err.NO_ERR;

        eAssert(null != mPlayerv.findViewById(R.id.music_player_layout_magic_id));
        initPlayerView(playerv);

        return Err.NO_ERR;
    }

    public void
    unsetController(Context context) {
        if (null != mVContext && context != mVContext)
            logW("YTJSPlayer : Unset Controller at different context...");

        mPlayerv = null;
        mVContext = null;

    }

    private Video[]
    getVideos(Cursor c, int coliTitle, int coliUrl, int coliVolume, boolean shuffle) {
        if (!c.moveToFirst())
            return new Video[0];

        Video[] vs = new Video[c.getCount()];

        int i = 0;
        if (!shuffle) {
            do {
                vs[i++] = new Video(c.getString(coliUrl),
                                    c.getString(coliTitle),
                                    c.getInt(coliVolume));
            } while (c.moveToNext());
            Arrays.sort(vs, sVideoTitleComparator);
        } else {
            // This is shuffled case!
            Random r = new Random(System.currentTimeMillis());
            NrElem[] nes = new NrElem[c.getCount()];
            do {
                nes[i++] = new NrElem(r.nextInt(),
                                      new Video(c.getString(coliUrl),
                                                c.getString(coliTitle),
                                                c.getInt(coliVolume)));
            } while (c.moveToNext());
            Arrays.sort(nes, sNrElemComparator);
            for (i = 0; i < nes.length; i++)
                vs[i] = (Video)nes[i].tag;
        }
        return vs;
    }

    public void
    startVideos(final Video[] vs) {
        eAssert(Utils.isUiThread());
        eAssert(null != mPlayerv);

        if (vs.length <= 0)
            return;

        // Stop if player is already running.
        ajsStop();

        releaseLocks();
        acquireLocks();

        mVideos = vs;
        mVideoi = 0;

        playVideo(mVideos[mVideoi].videoId, mVideos[mVideoi].volume);
    }

    public void
    startVideos(final Cursor c,
                final int coliUrl, final int coliTitle, final int coliVolume,
                final boolean shuffle) {
        eAssert(Utils.isUiThread());
        eAssert(null != mPlayerv);

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                final Video[] vs = getVideos(c, coliTitle, coliUrl, coliVolume, shuffle);
                Utils.getUiHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        startVideos(vs);
                    }
                });
                c.close();
            }
        });
    }

    public void
    stopVideos() {
        ajsStop();
        mVideos = null;
        mVideoi = -1;
    }

    public void
    setVideoVolume(int vol) {
        eAssert(0 <= vol && vol <= 100);
        if (null != mWv)
            ajsSetVolume(vol);
    }

    // ============================================================================
    //
    //
    //
    // ============================================================================

    public boolean
    isVideoPlaying() {
        if (null != mVideos
            && 0 <= mVideoi
            && mVideoi < mVideos.length)
            return true;
        return false;
    }
}
