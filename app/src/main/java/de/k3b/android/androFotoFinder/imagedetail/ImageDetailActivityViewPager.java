/*******************************************************************************
 * Copyright 2011, 2012 Chris Banes.
 * Copyright (c) 2015 by k3b.
 *
 * This file is part of AndroFotoFinder.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>
 */
package de.k3b.android.androFotoFinder.imagedetail;

import android.app.ActionBar;
import android.app.Activity;
import android.app.LoaderManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

// import com.squareup.leakcanary.RefWatcher;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import de.k3b.android.androFotoFinder.Common;
import de.k3b.android.androFotoFinder.FotoGalleryActivity;
import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.R;
import de.k3b.android.androFotoFinder.SettingsActivity;
import de.k3b.android.androFotoFinder.directory.DirectoryPickerFragment;
import de.k3b.android.androFotoFinder.locationmap.GeoEditActivity;
import de.k3b.android.androFotoFinder.locationmap.MapGeoPickerActivity;
import de.k3b.android.androFotoFinder.queries.FotoSql;
import de.k3b.android.util.AndroidFileCommands;
import de.k3b.android.util.IntentUtil;
import de.k3b.android.util.MediaScanner;
import de.k3b.android.util.SelectedFotos;
import de.k3b.android.widget.AboutDialogPreference;
import de.k3b.android.widget.Dialogs;
import de.k3b.android.widget.LocalizedActivity;
import de.k3b.database.QueryParameter;
import de.k3b.io.GalleryFilterParameter;
import de.k3b.io.IDirectory;
import de.k3b.io.OSDirectory;

/**
 * Shows a zoomable imagee.<br>
 * Swipe left/right to show previous/next image.
 */

public class ImageDetailActivityViewPager extends LocalizedActivity implements Common {
    private static final String INSTANCE_STATE_MODIFY_COUNT = "mModifyCount";
    public static final int ACTIVITY_ID = 76621;

    /** activityRequestCode: in forward mode: intent is forwarded to gallery */
    private static final int ID_FORWARD = 471102;
    private static final int DEFAULT_SORT = FotoSql.SORT_BY_NAME_LEN;
    private static final QueryParameter DEFAULT_QUERY = FotoSql.queryDetail;
    private static final int NO_INITIAL_SCROLL_POSITION = -1;

    /** if smaller that these millisecs then the actionbar autohide is disabled */
    private static final int DISABLE_HIDE_ACTIONBAR = 700;

    // how many changes have been made. if != 0 parent activity must invalidate cached data
    private static int mModifyCount = 0;
    private MenuItem mMenuSlideshow = null;

    class LocalCursorLoader implements LoaderManager.LoaderCallbacks<Cursor> {
        /** incremented every time a new curster/query is generated */
        private int mRequeryInstanceCount = 0;

        /** called by LoaderManager.getLoader(ACTIVITY_ID) to (re)create loader
         * that attaches to last query/cursor if it still exist i.e. after rotation */
        @Override
        public Loader<Cursor> onCreateLoader(int loaderID, Bundle bundle) {
            switch (loaderID) {
                case ACTIVITY_ID:
                    mRequeryInstanceCount++;
                    if (Global.debugEnabledSql) {
                        Log.i(Global.LOG_CONTEXT, mDebugPrefix + " onCreateLoader" +
                                getDebugContext() +
                                " : query = " + mGalleryContentQuery);
                    }
                    return FotoSql.createCursorLoader(getApplicationContext(), mGalleryContentQuery);
                default:
                    // An invalid id was passed in
                    return null;
            }
        }

        /** called after media db content has changed */
        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            // to be restored after reload if there is no mInitialFilePath
            if (mInitialScrollPosition == NO_INITIAL_SCROLL_POSITION) {
                mInitialScrollPosition = mViewPager.getCurrentItem();
            }
            // do change the data
            mAdapter.swapCursor(data);

            // restore position is invalid
            final int newItemCount = mAdapter.getCount();

            if (((newItemCount == 0)) || (mInitialScrollPosition >= newItemCount)) mInitialScrollPosition = NO_INITIAL_SCROLL_POSITION;

            if (Global.debugEnabledSql) {
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + " onLoadFinished" +
                        getDebugContext() +
                        " found " + ((data == null) ? 0 : newItemCount) + " rows");
            }

            // do change the data
            mAdapter.notifyDataSetChanged();
            mViewPager.setAdapter(mAdapter);

            // show the changes
            onLoadCompleted();
        }

        /** called by LoaderManager. after search criteria were changed or if activity is destroyed. */
        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            // rember position where we have to scroll to after reload is finished.
            mInitialScrollPosition = mViewPager.getCurrentItem();
            mAdapter.swapCursor(null);
            if (Global.debugEnabledSql) {
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + " onLoaderReset" +
                        getDebugContext());
            }
            mAdapter.notifyDataSetChanged();
        }

        @NonNull
        private String getDebugContext() {
            return "(#" + mRequeryInstanceCount
                    + ", mScrollPosition=" + mInitialScrollPosition +
                    ",  Path='" + mInitialFilePath +
                    "')";
        }
    }
    LocalCursorLoader mCurorLoader;

    class LocalFileCommands extends AndroidFileCommands {
        @Override
        protected void onPostProcess(String what, String[] oldPathNames, String[] newPathNames, int modifyCount, int itemCount, int opCode) {
            mInitialFilePath = null;
            switch (opCode) {
                case OP_MOVE:
                case OP_RENAME:
                    if ((newPathNames!= null) && (newPathNames.length > 0)) {
                        // so selection will be restored to this after load complete
                        mInitialFilePath = newPathNames[0];
                    }
                    break;
                case OP_COPY:
                    if ((oldPathNames!= null) && (oldPathNames.length > 0)) {
                        // so selection will be restored to this after load complete
                        mInitialFilePath = oldPathNames[0];
                    }
                    break;
            }

            super.onPostProcess(what, oldPathNames, newPathNames, modifyCount, itemCount, opCode);
        }

    }

    public static class MoveOrCopyDestDirPicker extends DirectoryPickerFragment {
        static AndroidFileCommands sFileCommands = null;

        public static MoveOrCopyDestDirPicker newInstance(boolean move, SelectedFotos srcFotos) {
            MoveOrCopyDestDirPicker f = new MoveOrCopyDestDirPicker();

            // Supply index input as an argument.
            Bundle args = new Bundle();
            args.putBoolean("move", move);
            args.putSerializable("srcFotos", srcFotos);
            f.setArguments(args);

            return f;
        }

        /** do not use activity callback */
        @Override
        protected void setDirectoryListener(Activity activity) {}

        public boolean getMove() {
            return getArguments().getBoolean("move", false);
        }

        public SelectedFotos getSrcFotos() {
            return (SelectedFotos) getArguments().getSerializable("srcFotos");
        }

        @Override
        protected void onDirectoryPick(IDirectory selection) {
            // super.onDirectoryPick(selection);
            mModifyCount++; // copy or move initiated
            getActivity().setResult((mModifyCount == 0) ? RESULT_NOCHANGE : RESULT_CHANGE);

            dismiss();
            sFileCommands.onMoveOrCopyDirectoryPick(getMove(), selection, getSrcFotos());
        }
    };

    private static final String INSTANCE_STATE_LAST_SCROLL_POSITION = "lastScrollPosition";

    // private static final String ISLOCKED_ARG = "isLocked";
	
	private LockableViewPager mViewPager = null;
    private ImagePagerAdapterFromCursor mAdapter = null;

    private final AndroidFileCommands mFileCommands = new LocalFileCommands();

    // for debugging
    private static int id = 1;
    private String mDebugPrefix;

    /** where data comes from */
    private QueryParameter mGalleryContentQuery = null;
    private GalleryFilterParameter mFilter = null;

    /** if >= 0 after load cursor scroll to this offset */
    private int mInitialScrollPosition = NO_INITIAL_SCROLL_POSITION;

    /** if not null: after load cursor scroll to this path */
    private String mInitialFilePath = null;

    public static void showActivity(Activity context, Uri imageUri, int position, QueryParameter imageDetailQuery) {
        Intent intent;
        //Create intent
        intent = new Intent(context, ImageDetailActivityViewPager.class);

        if (imageDetailQuery != null) {
            intent.putExtra(ImageDetailActivityViewPager.EXTRA_QUERY, imageDetailQuery.toReParseableString());
        }
        intent.putExtra(ImageDetailActivityViewPager.EXTRA_POSITION, position);
        intent.setData(imageUri);

        context.startActivityForResult(intent, ACTIVITY_ID);
    }

    @Override
	public void onCreate(Bundle savedInstanceState) {
        mDebugPrefix = "ImageDetailActivityViewPager#" + (id++)  + " ";
        Global.debugMemory(mDebugPrefix, "onCreate");

        // #17: let actionbar overlap image so there is no need to resize main view item
        // http://stackoverflow.com/questions/6749261/custom-translucent-android-actionbar
        getWindow().requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY);

        super.onCreate(savedInstanceState);
        Intent intent = getIntent();

        if (mustForward(intent)) {
            // cannot handle myself. Forward to FotoGalleryActivity
            Intent childIntent = new Intent();
            childIntent.setClass(this, FotoGalleryActivity.class);
            childIntent.setAction(intent.getAction());
            childIntent.setDataAndType(intent.getData(), intent.getType());
            copyExtras(childIntent, intent.getExtras(),
                    EXTRA_FILTER, EXTRA_POSITION, EXTRA_QUERY, EXTRA_SELECTED_ITEMS, EXTRA_STREAM, EXTRA_TITLE);
            startActivityForResult(childIntent, ID_FORWARD);
        } else { // not in forward mode
            setContentView(R.layout.activity_image_view_pager);

            mViewPager = (LockableViewPager) findViewById(R.id.view_pager);
            setContentView(mViewPager);

            // extra parameter
            getParameter(intent);

            mAdapter = new ImagePagerAdapterFromCursor(this, mDebugPrefix);
            mViewPager.setAdapter(mAdapter);
            mViewPager.setOnInterceptTouchEvent(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onGuiTouched();
                }
            });

            if (savedInstanceState != null) {
                mInitialScrollPosition = savedInstanceState.getInt(INSTANCE_STATE_LAST_SCROLL_POSITION, this.mInitialScrollPosition);
                mModifyCount = savedInstanceState.getInt(INSTANCE_STATE_MODIFY_COUNT, this.mModifyCount);
            } else {
                mModifyCount = 0;
            }

            setResult((mModifyCount == 0) ? RESULT_NOCHANGE : RESULT_CHANGE);

            mFileCommands.setContext(this);
            mFileCommands.setLogFilePath(mFileCommands.getDefaultLogFile());
            MoveOrCopyDestDirPicker.sFileCommands = mFileCommands;

            mCurorLoader = new LocalCursorLoader();
            getLoaderManager().initLoader(ACTIVITY_ID, null, mCurorLoader);

            // #17: make actionbar background nearly transparent
            // http://stackoverflow.com/questions/6749261/custom-translucent-android-actionbar
            getActionBar().setBackgroundDrawable(new ColorDrawable(Color.argb(128, 0, 0, 0)));
        }
        unhideActionBar(Global.actionBarHideTimeInMilliSecs, "onCreate");
    }

    private void onGuiTouched() {
        // stop slideshow if active
        if (mSlideShowStarted) startStopSlideShow(false);
        unhideActionBar(Global.actionBarHideTimeInMilliSecs, "onGuiTouched");
    }

    /** after 2 secs of user inactive the actionbar is hidden until the screen is touched */
    private Handler mActionBarHideTimer = null;
    private void unhideActionBar(int milliSecsUntilHide, String why) {
        final int ACTIONBAR_HIDE_HANDLER_ID = 3;
        boolean timer = milliSecsUntilHide >= DISABLE_HIDE_ACTIONBAR;

        ActionBar bar = getActionBar();

        if ((bar != null) && (!bar.isShowing())) {
            if (Global.debugEnabled) {
                Log.d(Global.LOG_CONTEXT, mDebugPrefix + "unhiding ActionBar(timer=" + timer +
                        ") " + why);
            }
            bar.show();
        }

        if (timer) {
            if (mActionBarHideTimer == null) {
                mActionBarHideTimer = new Handler() {
                    public void handleMessage(Message m) {
                        ActionBar bar = getActionBar();

                        if ((bar != null) && (bar.isShowing())) {
                            if (Global.debugEnabled) {
                                Log.d(Global.LOG_CONTEXT, mDebugPrefix + "hiding ActionBar");
                            }
                            bar.hide();
                        }
                    }
                };

            }
            mActionBarHideTimer.removeMessages(ACTIONBAR_HIDE_HANDLER_ID);
            mActionBarHideTimer.sendMessageDelayed(Message.obtain(mActionBarHideTimer, ACTIONBAR_HIDE_HANDLER_ID), milliSecsUntilHide);
        } else {
            if (mActionBarHideTimer != null) {
                mActionBarHideTimer.removeMessages(ACTIONBAR_HIDE_HANDLER_ID);
                mActionBarHideTimer = null;
            }
        }
    }

    private void copyExtras(Intent dest, Bundle source, String... keys) {
        if ((dest != null) && (source != null) && (keys != null)) {
            for (String key : keys) {
                Object item = source.get(key);
                if (item != null) {
                    dest.putExtra(key, item.toString());
                }
            }
        }
    }

    @Override
    protected void onActivityResult(final int requestCode,
                                    final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        if (requestCode == ID_FORWARD) {
            // forward result from child-activity to parent-activity
            setResult(resultCode, intent);
            finish();
        }
    }

    private static boolean mustForward(Intent intent) {
        Uri uri = IntentUtil.getUri(intent);
        File file = IntentUtil.getFile(uri);

        // probably content: url
        if (file == null) return IntentUtil.isFileUri(uri); // true if file:// with wildcards "%" or "*" goto gallery;

        // file with wildcard, directory or no read permissions
        return (!file.exists() || !file.isFile() || !file.canRead());
    }

    private void setFilter(GalleryFilterParameter value) {
        if (value != null) {
            QueryParameter query = new QueryParameter(DEFAULT_QUERY);
            FotoSql.setSort(query, DEFAULT_SORT, true);
            FotoSql.setWhereFilter(query, value, true);
            mGalleryContentQuery = query;
        }
        this.mFilter = value; // #34

    }

    /** query from EXTRA_QUERY, EXTRA_FILTER, fileParentDir , defaultQuery */
    private void getParameter(Intent intent) {
        this.mInitialScrollPosition = intent.getIntExtra(EXTRA_POSITION, this.mInitialScrollPosition);
        this.mGalleryContentQuery = QueryParameter.parse(intent.getStringExtra(EXTRA_QUERY));

        this.mFilter = null;
        if (mGalleryContentQuery == null) {
            String filterValue = intent.getStringExtra(EXTRA_FILTER);
            if (filterValue != null) {
                setFilter(GalleryFilterParameter.parse(filterValue, new GalleryFilterParameter()));
            }
        }

        if (mGalleryContentQuery == null) {
            this.mInitialScrollPosition = NO_INITIAL_SCROLL_POSITION;
            Uri uri = IntentUtil.getUri(intent);
            if (uri != null) {
                String scheme = uri.getScheme();
                if ((scheme == null) || ("file".equals(scheme))) {
                    setFilter(getParameterFromPath(uri.getPath(), true)); // including path and index
                } else if ("content".equals(scheme)) {
                    String path = FotoSql.execGetFotoPath(this, uri);
                    if (path != null) {
                        setFilter(getParameterFromPath(path, false));
                        if (Global.debugEnabled) {
                            Log.i(Global.LOG_CONTEXT, "Translate from '" + uri +
                                    "' to '" + path + "'");
                        }
                    } else {
                        Log.i(Global.LOG_CONTEXT, "Cannot translate from '" + uri +
                                "' to local file");
                    }
                }
            }
        }

        if ((mGalleryContentQuery == null) && (mFilter == null)) {
            Log.e(Global.LOG_CONTEXT, mDebugPrefix + " onCreate() : parameter Not found " + EXTRA_QUERY +
                    ", " + EXTRA_FILTER + ", ] not found. Using default.");
            mGalleryContentQuery = new QueryParameter(DEFAULT_QUERY);
            this.mInitialScrollPosition = NO_INITIAL_SCROLL_POSITION;
            //mInitialFilePath = path;
            //this.mInitialScrollPosition = NO_INITIAL_SCROLL_POSITION;
        } else if (Global.debugEnabledSql) {
            Log.i(Global.LOG_CONTEXT, mDebugPrefix + " onCreate() : query = " + mGalleryContentQuery + "; filter = " + mFilter);
        }
    }

    private GalleryFilterParameter getParameterFromPath(String path, boolean isFileUri) {
        if ((path == null) || (path.length() == 0)) return null;

        File selectedPhoto = new File(path);
        mInitialFilePath = path;
        this.mInitialScrollPosition = NO_INITIAL_SCROLL_POSITION;
        GalleryFilterParameter filter = new GalleryFilterParameter().setPath(selectedPhoto.getParent() + "/%");
        return filter;
    }

/* these doe not work yet (tested with for android 4.0)
    manifest         <activity ... android:ellipsize="middle" />

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        // http://stackoverflow.com/questions/10779037/set-activity-title-ellipse-to-middle
        final int ActionBarTitle = android.R.id.title; //  Resources.getSystem().getIdentifier("action_bar_title", "id", "android");
        final TextView titleView = (TextView)  this.getWindow().findViewById(ActionBarTitle);
        if ( titleView != null ) {
            titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        }
    }
*/

    @Override
    protected void onPause () {
        unhideActionBar(DISABLE_HIDE_ACTIONBAR, "onPause");
        Global.debugMemory(mDebugPrefix, "onPause");
        startStopSlideShow(false);
        super.onPause();
    }

    @Override
    protected void onResume () {
        unhideActionBar(Global.actionBarHideTimeInMilliSecs, "onResume");
        Global.debugMemory(mDebugPrefix, "onResume");
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        Global.debugMemory(mDebugPrefix, "onDestroy");

        unhideActionBar(DISABLE_HIDE_ACTIONBAR, "onDestroy");

        // getLoaderManager().destroyLoader(ACTIVITY_ID);
        if (mAdapter != null) {
            mViewPager.setAdapter(null);
            mFileCommands.closeLogFile();
            mFileCommands.closeAll();
            mFileCommands.setContext(null);
            MoveOrCopyDestDirPicker.sFileCommands = null;
        }

        if (mSlideShowTimer != null) {
            startStopSlideShow(false);
            mSlideShowTimer = null;
        }
        super.onDestroy();
        // RefWatcher refWatcher = AndroFotoFinderApp.getRefWatcher(this);
        // refWatcher.watch(this);
    }

    private void onLoadCompleted() {
        if (mAdapter.getCount() == 0) {
            // image not found in media database

            if (checkForIncompleteMediaDatabase(mInitialFilePath, "onLoadCompleted().count=0")) {
                // this.finish();
            } else {

                // close activity if last image of current selection has been deleted
                String message = getString(R.string.image_err_not_found_format, mInitialFilePath);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                this.finish();
            }
        } else if (mInitialFilePath != null) {
            // try to find selection by text
            mViewPager.invalidate();
            int positionFound = mAdapter.getCursorFromPath(mInitialFilePath);
            if (positionFound < 0) {
                // not found
                checkForIncompleteMediaDatabase(mInitialFilePath, "onLoadCompleted(Selection='" +
                        mInitialFilePath + "' not found)");
                if (mInitialScrollPosition >= 0) mViewPager.setCurrentItem(mInitialScrollPosition);
                // mInitialFilePath = null; keep path so next requery/rotatate the selected image will be displayed
            } else {
                mViewPager.setCurrentItem(positionFound);
                mInitialFilePath = null;
            }
            mInitialScrollPosition = NO_INITIAL_SCROLL_POSITION;
        } else if (mInitialScrollPosition >= 0) {
            // after initial load select correct image
            mViewPager.invalidate();
            mViewPager.setCurrentItem(mInitialScrollPosition);
            mInitialScrollPosition = NO_INITIAL_SCROLL_POSITION;
            mInitialFilePath = null;
        }
    }

    /** gets called if no file is found by a db-query or if jpgFullFilePath is not found in media db */
    private boolean checkForIncompleteMediaDatabase(String jpgFullFilePath, String why) {
        File fileToLoad = (jpgFullFilePath != null) ? new File(jpgFullFilePath) : null;
        if ((fileToLoad != null) && (fileToLoad.exists()) && (fileToLoad.canRead())) {
            // file exists => must update media database
            int numberOfNewItems = updateIncompleteMediaDatabase(mDebugPrefix, this,
                    mDebugPrefix + "checkForIncompleteMediaDatabase-" + why,
                    fileToLoad.getParentFile());

            String message = getString(R.string.image_err_not_in_db_format, jpgFullFilePath, numberOfNewItems);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }

    private static int updateIncompleteMediaDatabase(String debugPrefix, Context context, String why, File dirToScan) {
        if (dirToScan == null) return 0;

        String dbPathSearch = null;
        ArrayList<String> missing = new ArrayList<String>();
        dbPathSearch = dirToScan.getPath() + "/%";
        List<String> known = FotoSql.execGetFotoPaths(context, dbPathSearch);
        File[] existing = dirToScan.listFiles();

        if (existing != null) {
            for (File file : existing) {
                String found = file.getAbsolutePath();
                if (MediaScanner.isJpeg(found) && !known.contains(found)) {
                    missing.add(found);
                }
            }
        }

        if (Global.debugEnabled) {
            StringBuilder message = new StringBuilder();
            message.append(debugPrefix).append("updateIncompleteMediaDatabase('")
                    .append(dbPathSearch).append("') : \n\t");

            for(String s : missing) {
                message.append(s).append("; ");
            }
            Log.d(Global.LOG_CONTEXT, message.toString());
        }

        MediaScanner scanner = new MediaScanner(context, why);
        scanner.execute(null, missing.toArray(new String[missing.size()]));
        return missing.size();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_image_detail, menu);
        getMenuInflater().inflate(R.menu.menu_image_commands, menu);
        mMenuSlideshow = menu.findItem(R.id.action_slideshow);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // have more time to find and press the menu
        unhideActionBar(Global.actionBarHideTimeInMilliSecs * 3, "onPrepareOptionsMenu");
        AboutDialogPreference.onPrepareOptionsMenu(this, menu);

        MenuItem item = menu.findItem(R.id.cmd_show_geo);

        if (item != null) {
            item.setVisible(hasCurrentGeo());
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean slideShowStarted = mSlideShowStarted;

        onGuiTouched();
        if (mFileCommands.onOptionsItemSelected(item, getCurrentFoto())) {
            mModifyCount++;
            return true; // case R.id.cmd_delete:
        }

        // Handle presses on the action bar items
        switch (item.getItemId()) {
            case R.id.action_details:
                cmdShowDetails(getCurrentFilePath(), getCurrentImageId());
                return true;

            case R.id.action_slideshow:
                // only if not started
                if (!slideShowStarted) startStopSlideShow(true);
                return true;

            case R.id.action_edit:
                cmdStartIntent(getCurrentFilePath(), null, Intent.ACTION_EDIT, R.string.edit_chooser_title, R.string.edit_err_editor_not_found);
                return true;

            case R.id.menu_item_share:
                cmdStartIntent(null, getCurrentFilePath(), Intent.ACTION_SEND, R.string.share_menu_title, R.string.share_err_not_found);
                return true;

            case R.id.cmd_copy:
                return cmdMoveOrCopyWithDestDirPicker(false, mFileCommands.getLastCopyToPath(), getCurrentFoto());
            case R.id.cmd_move:
                return cmdMoveOrCopyWithDestDirPicker(true, mFileCommands.getLastCopyToPath(), getCurrentFoto());
            case R.id.menu_item_rename:
                return onRenameDirQueston(getCurrentImageId(), getCurrentFilePath(), null);

            case R.id.cmd_gallery:
                if (mFilter != null) {
                    FotoGalleryActivity.showActivity(this, this.mFilter, null, 0);
                } else {
                    FotoGalleryActivity.showActivity(this, null, this.mGalleryContentQuery, 0);
                }
                return true;

            case R.id.cmd_show_geo:
                MapGeoPickerActivity.showActivity(this, null, null, getCurrentFoto(), null, 0);
                return true;
            case R.id.cmd_edit_geo:
                GeoEditActivity.showActivity(this, getCurrentFoto());
                return true;

            case R.id.cmd_about:
                AboutDialogPreference.createAboutDialog(this).show();
                return true;
            case R.id.cmd_settings:
                SettingsActivity.show(this);
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }

    }

    private static final int SLIDESHOW_HANDLER_ID = 2;
    private boolean mSlideShowStarted = false;
    private Handler mSlideShowTimer = new Handler() {
        public void handleMessage(Message m) {
            if (mSlideShowStarted) {
                onSlideShowNext();
                sendMessageDelayed(Message.obtain(this, SLIDESHOW_HANDLER_ID), Global.slideshowIntervalInMilliSecs);
            }
        }
    };

    private void startStopSlideShow(boolean start) {
        mViewPager.setLocked(start);
        if (start != mSlideShowStarted) {
            if (start) {
                onSlideShowNext();
                mSlideShowTimer.sendMessageDelayed(Message.obtain(mSlideShowTimer, SLIDESHOW_HANDLER_ID), Global.slideshowIntervalInMilliSecs);
            } else {
                mSlideShowTimer.removeMessages(SLIDESHOW_HANDLER_ID);
            }
            mSlideShowStarted = start;

            // #24 Prevent sleepmode while slideshow is active
            if (this.mViewPager != null) this.mViewPager.setKeepScreenOn(start);
            
            if (mMenuSlideshow != null) mMenuSlideshow.setChecked(start);
        }
    }

    private void onSlideShowNext() {
        int pos = mViewPager.getCurrentItem() + 1;
        if (pos >= mAdapter.getCount()) pos = 0;
        mViewPager.setCurrentItem(pos);
    }

    private void cmdStartIntent(String currentFilePath, String extraPath, String action, int idChooserCaption, int idEditError) {

        final Intent outIntent = new Intent()
                .setAction(action)
                // .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (currentFilePath != null) {
            File file = new File(currentFilePath);
            final Uri uri = Uri.fromFile(file);
            outIntent.setDataAndType(uri, getMime(currentFilePath));
        }

        if (extraPath != null) {
            File file = new File(extraPath);
            final Uri uri = Uri.fromFile(file);
            outIntent.setType(getMime(extraPath));
            outIntent.putExtra(EXTRA_STREAM, uri);
        }
        if (Global.debugEnabled) {
            Log.d(Global.LOG_CONTEXT,
                    "cmdStartIntent(" + outIntent.toUri(Intent.URI_INTENT_SCHEME) + "')");
        }

        try {
            this.startActivity(Intent.createChooser(outIntent, getText(idChooserCaption)));
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, idEditError,Toast.LENGTH_LONG).show();
        }
    }

    private void cmdShowDetails(String fullFilePath, long currentImageId) {

        ImageDetailDialogBuilder.createImageDetailDialog(this, fullFilePath, currentImageId, mGalleryContentQuery, mViewPager.getCurrentItem()).show();
    }

    private boolean cmdMoveOrCopyWithDestDirPicker(final boolean move, String lastCopyToPath, final SelectedFotos fotos) {
        if (AndroidFileCommands.canProcessFile(this)) {
            MoveOrCopyDestDirPicker destDir = MoveOrCopyDestDirPicker.newInstance(move, fotos);

            destDir.defineDirectoryNavigation(new OSDirectory("/", null),
                    (move) ? FotoSql.QUERY_TYPE_GROUP_MOVE : FotoSql.QUERY_TYPE_GROUP_COPY,
                    lastCopyToPath);
            destDir.setContextMenuId(R.menu.menu_context_osdir);
            destDir.show(this.getFragmentManager(), "osdirimage");
        }
        return false;
    }

    private boolean onRenameDirQueston(final long fotoId, final String fotoPath, String newName) {
        if (AndroidFileCommands.canProcessFile(this)) {
            if (newName == null) {
                newName = new File(getCurrentFilePath()).getName();
            }

            Dialogs dialog = new Dialogs() {
                @Override
                protected void onDialogResult(String newFileName, Object... parameters) {
                    if (newFileName != null) {
                        onRenameSubDirAnswer((Long) parameters[0], (String) parameters[1], newFileName);
                    }
                }
            };
            dialog.editFileName(this, getString(R.string.rename_menu_title), newName, fotoId, fotoPath);
        }
        return true;
    }

    private void onRenameSubDirAnswer(final long fotoId, final String fotoSourcePath, String newFileName) {
        File src = new File(fotoSourcePath);
        File srcXmp = mFileCommands.getSidecar(src);
        boolean hasSideCar = ((srcXmp != null) && (mFileCommands.osFileExists(srcXmp)));

        File dest = new File(src.getParentFile(), newFileName);
        File destXmp = mFileCommands.getSidecar(dest);

        if (src == dest) return; // new name == old name ==> nothing to do

        String errorMessage = null;
        if (hasSideCar && mFileCommands.osFileExists(destXmp)) {
            errorMessage = getString(R.string.image_err_file_exists_format, destXmp.getAbsoluteFile());
        }
        if (mFileCommands.osFileExists(dest)) {
            errorMessage = getString(R.string.image_err_file_exists_format, dest.getAbsoluteFile());
        }

        if (errorMessage != null) {
            // dest-file already exists
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
            onRenameDirQueston(fotoId, fotoSourcePath, newFileName);
        } else if (mFileCommands.rename(fotoId, dest, src)) {
            mModifyCount++;
        } else {
            // rename failed
            errorMessage = getString(R.string.image_err_file_rename_format, src.getAbsoluteFile());
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        }
    }

    private String getMime(String path) {
        return "image/*";
        /*
        MimeTypeMap map = MimeTypeMap.getSingleton();
        return map.getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(path));
        */
    }

    protected SelectedFotos getCurrentFoto() {
        long imageId = getCurrentImageId();
        SelectedFotos result = new SelectedFotos();
        result.add(imageId);
        return  result;
    }

    private long getCurrentImageId() {
        int itemPosition = mViewPager.getCurrentItem();
        return this.mAdapter.getImageId(itemPosition);
    }

    protected String getCurrentFilePath() {
        int itemPosition = mViewPager.getCurrentItem();
        return this.mAdapter.getFullFilePath(itemPosition);
    }

    protected boolean hasCurrentGeo() {
        int itemPosition = mViewPager.getCurrentItem();
        return this.mAdapter.hasGeo(itemPosition);
    }

    private void toggleViewPagerScrolling() {
    	if (isViewPagerActive()) {
    		((LockableViewPager) mViewPager).toggleLock();
    	}
    }
    
    private boolean isViewPagerActive() {
    	return (mViewPager != null && mViewPager instanceof LockableViewPager);
    }

	@Override
	protected void onSaveInstanceState(Bundle outState) {
        if (isViewPagerActive()) {
            outState.putInt(INSTANCE_STATE_LAST_SCROLL_POSITION, mViewPager.getCurrentItem());
    	}
        outState.putInt(INSTANCE_STATE_MODIFY_COUNT, mModifyCount);
		super.onSaveInstanceState(outState);
	}

}
