package com.android.systemui.quicksettings;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.view.LayoutInflater; 

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;
import com.android.systemui.statusbar.phone.QuickSettingsController;


public class AlbumArtTile extends QuickSettingsTile {
    private final String TAG = "AlbumArtTile";
    private final boolean DBG = false;
    
    private static final int REFRESH = 1;
    private static final int GET_ALBUM_ART = 3;
    private static final int ALBUM_ART_DECODED = 4;
    
    private static final int SWIPE_MIN_DISTANCE = 120;
    private static final int SWIPE_MAX_OFF_PATH = 250;
    private static final int SWIPE_THRESHOLD_VELOCITY = 200;
    
    private static final Uri MUSIC_CONTENT_URI = Uri.parse("content://com.google.android.music.MusicContent");
    
    private static final String EMPTY = "";

    private Worker mAlbumArtWorker;
    private AlbumArtHandler mAlbumArtHandler;
    private boolean mAutoFlip;
    
    private CursorLoader mLoader;
    private CursorLoader mMusicLoader;
    private Cursor cAlbums;
    private Cursor cMusicAlbums;
    
    private String mArtist = EMPTY;
    private String mTrack = EMPTY;
    private String mAlbum = EMPTY;
    private boolean mPlaying = false;
    private long mSongId = -1;
    private long mAlbumId = -1;
    
    private PackageManager pm;
    String mMusicPackage = null;
    String mMusicService = null;
	private GestureDetector mGestureScanner;

    private Context mContext;
    private FrameLayout mContentView;
    private ImageView mBatteryImageView;
    private ImageView mImageView;
    private TextView mTextView;
    private String mTag
    
    public AlbumArtTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc,
            Handler handler) {
        super(context, inflater, container, qsc);

        mContext = context;
        mContentView = (FrameLayout)view;
        mBatteryImageView = (ImageView)mContentView.findViewById(R.id.quick_settings_battery_image);
        mImageView = (ImageView)mContentView.findViewById(R.id.quick_settings_iv);
        mTextView = (TextView)mContentView.findViewById(R.id.quick_settings_tv);
        
        pm = mContext.getPackageManager();
        mAlbumArtWorker = new Worker("album art worker");
        mAlbumArtHandler = new AlbumArtHandler(mAlbumArtWorker.getLooper());
        
        init();
    }
    
    @Override
    protected void init() {
        //mTag = TAG;
        
        mImageView.setVisibility(View.VISIBLE);
        Bitmap defaultImage = MusicUtils.getDefaultArtwork(mContext);
        mImageView.setImageBitmap(defaultImage);
        
        mBatteryImageView.setVisibility(View.VISIBLE);
        mBatteryImageView.setImageResource(R.drawable.btn_playback_play_normal_holo_dark);
        mBatteryImageView.setAlpha(0.0f);
        mBatteryImageView.setScaleType(ScaleType.FIT_CENTER);
        
        mTextView.setText(R.string.status_bar_settings_mediaplayer);
        mTextView.setBackgroundColor(mContext.getResources().getColor(R.color.qs_user_banner_background));
        
        adjustLayouts();
        
        // dont even bother going any further
        if(!findMusicService())return;
        
        mGestureScanner = new GestureDetector(mContext, new GestureScanner());
        mContentView.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				return mGestureScanner.onTouchEvent(event);
			}
		});
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(MusicUtils.PLAYSTATE_CHANGED);
        filter.addAction(MusicUtils.META_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
        
        mAutoFlip = true;
           
        loadAlbumsData();
        loadOnlineAlbumsData();
        updateTrackInfo();
    }
    
    public void openMusic() {
    	
    	if(findMusicService()){
    		launchActivity(new Intent("com.google.android.music.PLAYBACK_VIEWER")
	    		.setClassName(mMusicPackage, "com.android.music.activitymanagement.TopLevelActivity"));
    	}
    }
    
    private boolean findMusicService(){
        
        List<PackageInfo> list = pm.getInstalledPackages(PackageManager.GET_SERVICES);
        if(DBG)Log.d(TAG, "Found "+list.size()+" packages with services");

        for(PackageInfo pi: list){
            if(pi.packageName.equals("com.android.music") || pi.packageName.equals("com.google.android.music")){
                if(DBG)Log.d(TAG, "Found music under this package name: "+pi.packageName);
                if(pi.services.length > 0){
                    if(DBG)Log.d(TAG, "Music has services totaling: "+pi.services.length);
                    for(ServiceInfo service: pi.services){
                        if(DBG)Log.d(TAG, "Music service name: "+service.name);
                        if(service.name.contains("MusicPlaybackService")){
                            mMusicPackage = pi.packageName;
                            mMusicService = service.name;
                            if(DBG)Log.d(TAG, "Found the music service");
                            return true;
                        }
                    }
                }
            }
            
        }
        
        return false;
    }
    
    private void loadAlbumsData(){
        // load all of the albums into a cursor
        // we use this to compare to the meta data in order to
        // find the proper album id that we can use to 
        // grab the correct album art
        String[] projection = { MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM };
        String selection = "";
        String [] selectionArgs = null;
        mLoader = new CursorLoader(mContext, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null );
        mLoader.registerListener(0, mLoaderListener);
        mLoader.startLoading();
    }
    
    private void loadOnlineAlbumsData(){
        String[] projection = { MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM };
        String selection = "";
        String [] selectionArgs = null;
        
        mMusicLoader = new CursorLoader(mContext, Uri.withAppendedPath(MUSIC_CONTENT_URI, "audio"),
                projection, selection, selectionArgs, null );
        mMusicLoader.registerListener(1, mLoaderListener);
        mMusicLoader.startLoading();
    }
    
    private void sendMediaButtonEvent(String what){
        //TODO: allow the user to unlock this link to Google Music
        // so that this may work as a general media button event
        
        if(mMusicPackage == null || mMusicService == null){
            Log.w(TAG, "The Google music service was not found");
            Toast.makeText(mContext, "Google Music was not found", Toast.LENGTH_SHORT).show();
        }
        
        try{
            Intent intent = new Intent(what);
            intent.setClassName(mContext.createPackageContext(mMusicPackage, 0)
                    , mMusicService);
            mContext.startServiceAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        }catch(NameNotFoundException e){
            e.printStackTrace();
        }catch(SecurityException e){
            e.printStackTrace();
        }
    }

    @Override
    public void release() {
        
        mContext.unregisterReceiver(mReceiver);
        if(mLoader != null){
            mLoader.stopLoading();
            mLoader.unregisterListener(mLoaderListener);
        }
        if(mMusicLoader!=null){
            mMusicLoader.stopLoading();
            mMusicLoader.unregisterListener(mLoaderListener);
        }
        if(cAlbums!=null){
            cAlbums.close();
        }
        if(cMusicAlbums!=null){
            cMusicAlbums.close();
        }
    }

    @Override
    public void refreshResources() {
    	if(mLoader != null){
            mLoader.forceLoad();
        }
        if(mMusicLoader!=null){
            mMusicLoader.forceLoad();
        }
    }
    
    void adjustLayouts(){
    	final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ((LinearLayout)mTextView.getParent()).getLayoutParams();
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
        lp.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM;
        
        final LinearLayout.LayoutParams lp2 = (LinearLayout.LayoutParams) mTextView.getLayoutParams();
        lp2.width = LinearLayout.LayoutParams.MATCH_PARENT;
        lp2.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        lp2.gravity = Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM;
        
        final FrameLayout.LayoutParams lp3 = (FrameLayout.LayoutParams) ((LinearLayout)mBatteryImageView.getParent()).getLayoutParams();
        lp3.width = FrameLayout.LayoutParams.MATCH_PARENT;
        lp3.height = FrameLayout.LayoutParams.MATCH_PARENT;
        lp3.gravity = Gravity.CENTER_HORIZONTAL;
        
        final LinearLayout.LayoutParams lp4 = (LinearLayout.LayoutParams) mBatteryImageView.getLayoutParams();
        lp4.width = LinearLayout.LayoutParams.MATCH_PARENT;
        lp4.height = 0;
        lp4.gravity = Gravity.CENTER_HORIZONTAL;
        lp4.weight = 1;
    }
    
    private void updateTrackInfo() {

        try {

            
            long songid = mSongId;
            if (songid < 0) return;
            
            String artistName = mArtist;
            if (MediaStore.UNKNOWN_STRING.equals(artistName)) {
                artistName = "Unknown artist";
            }
            
            String trackName = mTrack;
            long albumid = mAlbumId;
            if (MediaStore.UNKNOWN_STRING.equals(trackName)) {
                artistName = "Unknown song";
                albumid = -1;
            }
            if(trackName.trim().equals(EMPTY)){
                trackName = mContext.getString(R.string.status_bar_settings_mediaplayer);
            }
            //mPlayer.setArtistAlbumText(artistName);
            //mPlayer.setTrackTitle(trackName);
            mTextView.setText(artistName+"-"+trackName);
            
            // lets search local music first
            if(cAlbums != null){
                int col_album = cAlbums.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int col_album_id = cAlbums.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                if(col_album >= 0 && col_album_id >= 0){
                    cAlbums.moveToFirst();
                    for(int x = 0; x < cAlbums.getCount(); x++){
                        if(DBG) Log.d(TAG, "row:"+x+" album:"+cAlbums.getString(col_album)
                                +" album id:"+cAlbums.getLong(col_album_id));
                        if(cAlbums.getString(col_album).equals(mAlbum)){
                            mAlbumId = cAlbums.getLong(col_album_id);
                            albumid = cAlbums.getLong(col_album_id);
                            break;
                        }
                        cAlbums.moveToNext();
                    }
                }
            }
            
            // now search for online music
            if(cMusicAlbums != null && albumid < 0){
                int col_album = cAlbums.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int col_album_id = cAlbums.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                if(col_album_id >= 0 && col_album >= 0){
                    cMusicAlbums.moveToFirst();
                    for(int x = 0; x < cMusicAlbums.getCount(); x++){
                        if(DBG) Log.d(TAG, "Music row:"+x+" album:"+cMusicAlbums.getString(col_album)
                                +" album id:"+cMusicAlbums.getLong(col_album_id));
                        if(cMusicAlbums.getString(col_album).equals(mAlbum)){
                            mSongId = cMusicAlbums.getLong(col_album_id);
                            songid = cMusicAlbums.getLong(col_album_id);
                            break;
                        }
                        cMusicAlbums.moveToNext();
                    }
                }
            }          
            
            /**
             * get the album art using a different thread
             */
            mAlbumArtHandler.removeMessages(GET_ALBUM_ART);
            mAlbumArtHandler.obtainMessage(GET_ALBUM_ART, new AlbumSongIdWrapper(albumid, songid)).sendToTarget();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    private void animatePlayPauseImage() {
        if(mPlaying){
        	mBatteryImageView.setImageResource(R.drawable.btn_playback_play_normal_holo_dark);
        }else{
        	mBatteryImageView.setImageResource(R.drawable.btn_playback_pause_normal_holo_dark);
        }
        final AnimatorSet anim = (AnimatorSet) AnimatorInflater.loadAnimator(mContext, R.anim.grow_and_fade);
		anim.setTarget(mBatteryImageView);
		anim.setDuration(500);
		anim.start();
    }
    
    public class GestureScanner implements OnGestureListener {
	    @Override
	    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
	        try {
	            if (Math.abs(e1.getY() - e2.getY()) > SWIPE_MAX_OFF_PATH)
	                return false;
	            // right to left swipe
	            if(e1.getX() - e2.getX() > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
	            	mAutoFlip = false;
	            	flipTile(R.anim.flip_left, "com.android.music.musicservicecommand.previous");
	            }  else if (e2.getX() - e1.getX() > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
	            	mAutoFlip = false;
	            	flipTile(R.anim.flip_right, "com.android.music.musicservicecommand.next");
	            }
	        } catch (Exception e) {
	            // nothing
	        }
	        return false;
	    }

		@Override
		public boolean onDown(MotionEvent e) {
			// TODO Auto-generated method stub
			return true;
		}

		@Override
		public void onShowPress(MotionEvent e) {
			// TODO Auto-generated method stub
		}

		@Override
		public boolean onSingleTapUp(MotionEvent e) {
			sendMediaButtonEvent("com.android.music.musicservicecommand.togglepause");
			return false;
		}

		@Override
		public boolean onScroll(MotionEvent e1, MotionEvent e2,
				float distanceX, float distanceY) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public void onLongPress(MotionEvent e) {
			openMusic();
		}
    }
    
    private void flipTile(int animId, final String mediaCommand){
    	final AnimatorSet anim = (AnimatorSet) AnimatorInflater.loadAnimator(mContext, animId);
		anim.setTarget(mContentView.getParent());
		anim.setDuration(400);		
		anim.addListener(new AnimatorListener(){

			@Override
			public void onAnimationEnd(Animator animation) {}

			@Override
			public void onAnimationStart(Animator animation) {
				if(mediaCommand!=null){
					sendMediaButtonEvent(mediaCommand);
				}
			}
			@Override
			public void onAnimationCancel(Animator animation) {}
			@Override
			public void onAnimationRepeat(Animator animation) {
			}
			
		});
		anim.start();
    }
    
    private BroadcastReceiver mReceiver = new BroadcastReceiver(){

        @Override
        public void onReceive(Context context, Intent intent) {
            
        	boolean changedPlayState = false;
        	
        	StringBuilder sb = new StringBuilder();
        	sb.append("mPlaying is the same: ");
        	sb.append(mPlaying==intent.getBooleanExtra("playstate", mPlaying));
        	sb.append("\n");
        	sb.append("mSongId: ");
        	sb.append(mSongId);
        	sb.append("\n");
        	sb.append("New songId: ");
        	sb.append(intent.getLongExtra("id", -1));
        	sb.append("\n");
        	
            mArtist = intent.getStringExtra("artist");
            mAlbum = intent.getStringExtra("album");
            mTrack = intent.getStringExtra("track");
            // do we need to show the play/pause image?
            if(mPlaying!=intent.getBooleanExtra("playstate", mPlaying)){
            	mPlaying = intent.getBooleanExtra("playstate", mPlaying);
            	changedPlayState = true;
            	mAutoFlip = false;
            }
            if(mSongId==intent.getLongExtra("id", -1)){
            	mAutoFlip = false;
            }
            mSongId = intent.getLongExtra("id", -1);
            mAlbumId = -1;
            
            sb.append("changedPlayState: ");
            sb.append(changedPlayState);
        	sb.append("\n");
        	sb.append("mAutoFlip: ");
        	sb.append(mAutoFlip);
        	
        	if(DBG)Log.d(TAG, sb.toString());
            
            updateTrackInfo();
            if(changedPlayState){
            	animatePlayPauseImage();
            }
        	if(mAutoFlip){
        		flipTile(R.anim.flip_right,null);
        	}
        	mAutoFlip = true;
        }
        
    };
    
    private final Loader.OnLoadCompleteListener<Cursor> mLoaderListener = 
            new Loader.OnLoadCompleteListener<Cursor>() {

        @Override
        public void onLoadComplete(Loader<Cursor> loader, Cursor data) {
            if(loader.getId()==0){
                cAlbums = data;   
            }else{
                cMusicAlbums = data;
            }
        }
    };
    
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ALBUM_ART_DECODED:
                	mImageView.setScaleType(ScaleType.CENTER_CROP);
                	mImageView.setImageBitmap((Bitmap)msg.obj);
                	mImageView.getDrawable().setDither(true);
                    break;
                case REFRESH:
                    break;
                default:
                    break;
            }
        }
    };
    
    public class AlbumArtHandler extends Handler {
        private long mAlbumId = -1;
        
        public AlbumArtHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg)
        {
            long albumid = ((AlbumSongIdWrapper) msg.obj).albumid;
            long songid = ((AlbumSongIdWrapper) msg.obj).songid;
            if (msg.what == GET_ALBUM_ART && (mAlbumId != albumid || albumid < 0)) {
                // while decoding the new image, show the default album art
                Message numsg = mHandler.obtainMessage(ALBUM_ART_DECODED, null);
                mHandler.removeMessages(ALBUM_ART_DECODED);
                mHandler.sendMessageDelayed(numsg, 300);
                // Don't allow default artwork here, because we want to fall back to song-specific
                // album art if we can't find anything for the album.
                Bitmap bm = MusicUtils.getArtwork(mContext, songid, albumid, false);
                if (bm == null) {
                    bm = MusicUtils.getArtwork(mContext, songid, -1);
                    albumid = -1;
                }
                if (bm != null) {
                    numsg = mHandler.obtainMessage(ALBUM_ART_DECODED, bm);
                    mHandler.removeMessages(ALBUM_ART_DECODED);
                    mHandler.sendMessage(numsg);
                }else{
                    Log.d(TAG, "album art returned null for "+songid);
                }
                mAlbumId = albumid;
            }
        }
    }
    
    private static class Worker implements Runnable {
        private final Object mLock = new Object();
        private Looper mLooper;
        
        /**
         * Creates a worker thread with the given name. The thread
         * then runs a {@link android.os.Looper}.
         * @param name A name for the new thread
         */
        Worker(String name) {
            Thread t = new Thread(null, this, name);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            synchronized (mLock) {
                while (mLooper == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }
        
        public Looper getLooper() {
            return mLooper;
        }
        
        public void run() {
            synchronized (mLock) {
                Looper.prepare();
                mLooper = Looper.myLooper();
                mLock.notifyAll();
            }
            Looper.loop();
        }
        
        public void quit() {
            mLooper.quit();
        }
    }
    
    private static class AlbumSongIdWrapper {
        public long albumid;
        public long songid;
        AlbumSongIdWrapper(long aid, long sid) {
            albumid = aid;
            songid = sid;
        }
    }
    
    private static class MusicUtils {
        private static int sArtId = -2;
        private static Bitmap mCachedBit = null;
        private static final BitmapFactory.Options sBitmapOptionsCache = new BitmapFactory.Options();
        private static final BitmapFactory.Options sBitmapOptions = new BitmapFactory.Options();
        private static final Uri sArtworkUri = Uri.parse("content://media/external/audio/albumart");
        private static final Uri MUSIC_CONTENT_URI = Uri.parse("content://com.google.android.music.MusicContent");
        
        public static final String PLAYSTATE_CHANGED = "com.android.music.playstatechanged";
        public static final String META_CHANGED = "com.android.music.metachanged";
        
        static {
            // for the cache, 
            // 565 is faster to decode and display
            // and we don't want to dither here because the image will be scaled down later
            sBitmapOptionsCache.inPreferredConfig = Bitmap.Config.RGB_565;
            sBitmapOptionsCache.inDither = false;

            sBitmapOptions.inPreferredConfig = Bitmap.Config.RGB_565;
            sBitmapOptions.inDither = false;
        }
                
        // get album art for specified file
        private static final String sExternalMediaUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString();
        private static Bitmap getArtworkFromFile(Context context, long songid, long albumid) {
            Bitmap bm = null;

            if (albumid < 0 && songid < 0) {
                throw new IllegalArgumentException("Must specify an album or a song id");
            }

            try {
                if (albumid < 0) {
                    // for online music
                    Log.d(TAG, "looking here for album art:"+MUSIC_CONTENT_URI+"/albumart/"+songid);
                    Uri uri = Uri.withAppendedPath(MUSIC_CONTENT_URI, "albumart/"+songid);
                    ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd != null) {
                        FileDescriptor fd = pfd.getFileDescriptor();
                        bm = BitmapFactory.decodeFileDescriptor(fd);
                    }
                } else {
                    // local music
                    Uri uri = ContentUris.withAppendedId(sArtworkUri, albumid);
                    Log.d(TAG, "looking here for album art:"+uri.toSafeString());
                    ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd != null) {
                        FileDescriptor fd = pfd.getFileDescriptor();
                        bm = BitmapFactory.decodeFileDescriptor(fd);
                    }
                }
            } catch (IllegalStateException ex) {
            } catch (FileNotFoundException ex) {
            }
            if (bm != null) {
                mCachedBit = bm;
            }
            return bm;
        }
        
        public static Bitmap getArtwork(Context context, long song_id, long album_id) {
            return getArtwork(context, song_id, album_id, true);
        }
        
        /** Get album art for specified album. You should not pass in the album id
         * for the "unknown" album here (use -1 instead)
         */
        public static Bitmap getArtwork(Context context, long song_id, long album_id,
                boolean allowdefault) {

            if (album_id < 0) {
                // This is something that is not in the database, so get the album art directly
                // from the file.
                if (song_id >= 0) {
                    Bitmap bm = getArtworkFromFile(context, song_id, -1);
                    if (bm != null) {
                        return bm;
                    }
                }
                if (allowdefault) {
                    return getDefaultArtwork(context);
                }
                return null;
            }

            ContentResolver res = context.getContentResolver();
            Uri uri = ContentUris.withAppendedId(sArtworkUri, album_id);
            if (uri != null) {
                InputStream in = null;
                try {
                    in = res.openInputStream(uri);
                    return BitmapFactory.decodeStream(in, null, sBitmapOptions);
                } catch (FileNotFoundException ex) {
                    // The album art thumbnail does not actually exist. Maybe the user deleted it, or
                    // maybe it never existed to begin with.
                    Bitmap bm = getArtworkFromFile(context, song_id, album_id);
                    if (bm != null) {
                        if (bm.getConfig() == null) {
                            bm = bm.copy(Bitmap.Config.RGB_565, false);
                            if (bm == null && allowdefault) {
                                return getDefaultArtwork(context);
                            }
                        }
                    } else if (allowdefault) {
                        bm = getDefaultArtwork(context);
                    }
                    return bm;
                } finally {
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (IOException ex) {
                    }
                }
            }
            
            return null;
        }
        
        private static Bitmap getArtworkFromUrl(Context context, String address){
            Bitmap bm = null;            
            try{
                URL url = new URL(address);
                InputStream content = (InputStream)url.getContent();
                Drawable d = Drawable.createFromStream(content , "src"); 
                bm = BitmapFactory.decodeStream(content);
            }catch(Exception e){
                e.printStackTrace();
            }
            
            return bm;
        }
        
        private static Bitmap getDefaultArtwork(Context context) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

            return BitmapFactory.decodeStream(
                    context.getResources().openRawResource(R.drawable.albumart_mp_unknown), null, opts);
        }
    }
private void launchActivity(Intent intent) throws ActivityNotFoundException {
     // We take this as a good indicator that Setup is running and we shouldn't
        // allow you to go somewhere else
        if (!isDeviceProvisioned()) return;
     try {
// The intent we are sending is for the application, which
// won't have permission to immediately start an activity after
// the user switches to home. We know it is safe to do at this
// point, so make sure new activity switches are now allowed.
ActivityManagerNative.getDefault().resumeAppSwitches();
// Also, notifications can be launched from the lock screen,
// so dismiss the lock screen when the activity starts.
ActivityManagerNative.getDefault()
.dismissKeyguardOnNextActivity();
} catch (RemoteException e) {
}

intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));

getStatusBarManager().collapsePanels();
    }

}
