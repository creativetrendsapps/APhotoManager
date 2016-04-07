/*
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
 
package de.k3b.android.androFotoFinder;

import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

// import com.squareup.leakcanary.RefWatcher;

import de.k3b.android.androFotoFinder.directory.DirectoryGui;
import de.k3b.android.androFotoFinder.directory.DirectoryLoaderTask;
import de.k3b.android.androFotoFinder.gallery.cursor.GalleryCursorFragment;
import de.k3b.android.androFotoFinder.imagedetail.ImageDetailActivityViewPager;
import de.k3b.android.androFotoFinder.locationmap.GeoEditActivity;
import de.k3b.android.androFotoFinder.locationmap.LocationMapFragment;
import de.k3b.android.androFotoFinder.directory.DirectoryPickerFragment;
import de.k3b.android.androFotoFinder.locationmap.MapGeoPickerActivity;
import de.k3b.android.androFotoFinder.queries.FotoSql;
import de.k3b.android.androFotoFinder.queries.FotoViewerParameter;
import de.k3b.android.androFotoFinder.queries.Queryable;
import de.k3b.android.util.GarbageCollector;
import de.k3b.android.util.IntentUtil;
import de.k3b.android.util.SelectedFotos;
import de.k3b.android.widget.AboutDialogPreference;
import de.k3b.android.widget.LocalizedActivity;
import de.k3b.database.QueryParameter;
import de.k3b.io.Directory;
import de.k3b.io.DirectoryFormatter;
import de.k3b.io.GalleryFilterParameter;
import de.k3b.io.GeoRectangle;
import de.k3b.io.IDirectory;
import de.k3b.io.IGalleryFilter;

public class FotoGalleryActivity extends LocalizedActivity implements Common,
        OnGalleryInteractionListener, DirectoryPickerFragment.OnDirectoryInteractionListener,
        LocationMapFragment.OnDirectoryInteractionListener
{
    private static final String mDebugPrefix = "GalleryA-";

    /** intent parameters supported by FotoGalleryActivity: EXTRA_... */

    private static final String DLG_NAVIGATOR_TAG = "navigator";
    private static final String STATE_CurrentSelections = "CurrentSelections";

    /** after media db change cached Directories must be recalculated */
    private final ContentObserver mMediaObserverDirectory = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            invalidateDirectories(mDebugPrefix + "#onChange from mMediaObserverDirectory");
        }
    };

    /** set while dir picker is active */
    private DialogFragment mDirPicker = null;

    private static class GalleryQueryParameter {
        private static final String STATE_CurrentPath = "CurrentPath";
        private static final String STATE_DirQueryID = "DirQueryID";
        private static final String STATE_SortID = "SortID";
        private static final String STATE_SortAscending = "SortAscending";
        private static final String STATE_Filter = "filter";
        private static final String STATE_LAT_LON = "currentLatLon";
        private static final String STATE_LAT_LON_ACTIVE = "currentLatLonActive";

        /** true use latLonPicker; false use directoryPicker */
        private boolean mUseLatLon = false;
        private GeoRectangle mCurrentLatLon = new GeoRectangle();

        /** one of the FotoSql.QUERY_TYPE_xxx values */
        int mDirQueryID = FotoSql.QUERY_TYPE_GROUP_DEFAULT;

        private boolean mHasUserDefinedQuery = false;
        private int mSortID = FotoSql.SORT_BY_DEFAULT;
        private boolean mSortAscending = true;

        private String mCurrentPath = "/";

        QueryParameter mGalleryContentQuery = null;

        // GalleryFilterParameter mFilter;
        IGalleryFilter mFilter;
        /** true: if activity started without special intent-parameters, the last mFilter is saved/loaded for next use */
        private boolean mSaveToSharedPrefs = true;

        /** one of the FotoSql.QUERY_TYPE_xxx values. if undefined use default */
        private int getDirQueryID() {
            if (this.mDirQueryID == FotoSql.QUERY_TYPE_UNDEFINED)
                return FotoSql.QUERY_TYPE_GROUP_DEFAULT;

            return this.mDirQueryID;
        }

        public int getSortID() {
            return mSortID;
        }
        public void setSortID(int sortID) {
            if (sortID == mSortID) {
                mSortAscending = !mSortAscending;
            } else {
                mSortAscending = true;
                mSortID = sortID;
            }
        }

        public String getSortDisplayName(Context context) {
            return  FotoSql.getName(context, this.mSortID) + ((mSortAscending) ? " ^" : " V");
        }

        public boolean clearPathIfActive() {
            if ((!mUseLatLon) && (mCurrentPath != null)) {
                mCurrentPath = null;
                return true;
            }
            return false;
        }

        /** combine root-query plus current selected directoryRoot */
        private QueryParameter calculateEffectiveGalleryContentQuery() {
            return calculateEffectiveGalleryContentQuery(mGalleryContentQuery);
        }

        /** combine root-query plus current selected directoryRoot */
        private QueryParameter calculateEffectiveGalleryContentQuery(QueryParameter rootQuery) {
            if (rootQuery == null) return null;
            QueryParameter result = new QueryParameter(rootQuery);

            FotoSql.setWhereFilter(result, this.mFilter, !hasUserDefinedQuery());
            if (result == null) return null;

            if (mUseLatLon) {
                FotoSql.addWhereFilterLatLon(result, mCurrentLatLon);
            } else if (this.mCurrentPath != null) {
                FotoSql.addPathWhere(result, this.mCurrentPath, this.getDirQueryID());
            }

            if (mSortID != FotoSql.SORT_BY_NONE) {
                FotoSql.setSort(result, mSortID, mSortAscending);
            }
            return result;
        }

        private void saveInstanceState(Context context, Bundle savedInstanceState) {
            saveSettings(context);

            // save InstanceState
            savedInstanceState.putInt(STATE_DirQueryID, this.getDirQueryID());
            savedInstanceState.putString(STATE_CurrentPath, this.mCurrentPath);
            savedInstanceState.putInt(STATE_SortID, this.mSortID);
            savedInstanceState.putBoolean(STATE_SortAscending, this.mSortAscending);
            if (this.mFilter != null) {
                savedInstanceState.putString(STATE_Filter, this.mFilter.toString());
            }
            savedInstanceState.putString(STATE_LAT_LON, this.mCurrentLatLon.toString());
            savedInstanceState.putBoolean(STATE_LAT_LON_ACTIVE, this.mUseLatLon);

        }

        private void saveSettings(Context context) {
            if (mSaveToSharedPrefs) {
                // save settings
                SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
                SharedPreferences.Editor edit = sharedPref.edit();

                edit.putInt(STATE_DirQueryID, this.getDirQueryID());
                edit.putString(STATE_CurrentPath, this.mCurrentPath);
                edit.putInt(STATE_SortID, this.mSortID);
                edit.putBoolean(STATE_SortAscending, this.mSortAscending);
                edit.putString(STATE_LAT_LON, this.mCurrentLatLon.toString());

                if (mFilter != null) {
                    edit.putString(STATE_Filter, mFilter.toString());
                }

                edit.commit();
            }
        }

        // load from settings/instanceState
        private void loadSettingsAndInstanceState(Activity context, Bundle savedInstanceState) {
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
            this.mCurrentPath = sharedPref.getString(STATE_CurrentPath, this.mCurrentPath);
            this.mDirQueryID = sharedPref.getInt(STATE_DirQueryID, this.getDirQueryID());
            this.mSortID = sharedPref.getInt(STATE_SortID, this.mSortID);
            this.mSortAscending = sharedPref.getBoolean(STATE_SortAscending, this.mSortAscending);
            this.mCurrentLatLon.get(DirectoryFormatter.parseLatLon(sharedPref.getString(STATE_LAT_LON, null)));

            Intent intent = context.getIntent();

            // for debugging: where does the filter come from
            StringBuilder dbgFilter = (Global.debugEnabled) ? new StringBuilder() : null;
            String filter = null;
            String pathFilter = null;

            if (intent != null) {
                filter = intent.getStringExtra(EXTRA_FILTER);

                if ((filter != null) && (dbgFilter != null)) dbgFilter.append("filter from ").append(EXTRA_FILTER).append("=").append(filter).append("\n");

                if (filter == null) {
                    Uri uri = IntentUtil.getUri(intent);

                    if (IntentUtil.isFileUri(uri)) {
                        pathFilter = uri.getSchemeSpecificPart();
                        if (pathFilter != null) pathFilter = pathFilter.replace('*', '%');
                        if (dbgFilter != null) dbgFilter.append("path from uri=").append(pathFilter).append("\n");
                    }
                }
            }
            this.mSaveToSharedPrefs = (filter == null); // false if controlled via intent
            // instance state overrides settings
            if (savedInstanceState != null) {
                this.mCurrentPath = savedInstanceState.getString(STATE_CurrentPath, this.mCurrentPath);
                this.mDirQueryID = savedInstanceState.getInt(STATE_DirQueryID, this.getDirQueryID());
                this.mSortID = savedInstanceState.getInt(STATE_SortID, this.mSortID);
                this.mSortAscending = savedInstanceState.getBoolean(STATE_SortAscending, this.mSortAscending);
                filter = savedInstanceState.getString(STATE_Filter);
                if ((filter != null) && (dbgFilter != null)) dbgFilter.append("filter from savedInstanceState=").append(filter).append("\n");

                this.mCurrentLatLon.get(DirectoryFormatter.parseLatLon(savedInstanceState.getString(STATE_LAT_LON)));

                this.mUseLatLon = savedInstanceState.getBoolean(STATE_LAT_LON_ACTIVE, this.mUseLatLon);
            }

            if ((pathFilter == null) && (filter == null) && (this.mFilter == null)) {
                filter = sharedPref.getString(STATE_Filter, null);
                if ((filter != null) && (dbgFilter != null)) dbgFilter.append("filter from sharedPref=").append(filter).append("\n");
            }

            if (filter != null) {
                this.mFilter = GalleryFilterParameter.parse(filter, new GalleryFilterParameter());
            } else if (pathFilter != null) {
                if (!pathFilter.endsWith("%")) pathFilter += "%";
                this.mFilter = new GalleryFilterParameter().setPath(pathFilter);
            }

            // extra parameter
            final String sqlString = intent.getStringExtra(EXTRA_QUERY);
            if (sqlString != null) {
                if (dbgFilter != null) dbgFilter.append("query from ").append(EXTRA_QUERY).append("\n\t").append(sqlString).append("\n");
                this.mGalleryContentQuery = QueryParameter.parse(sqlString);
                setHasUserDefinedQuery(true);
            }

            if (this.mGalleryContentQuery == null) this.mGalleryContentQuery = FotoSql.getQuery(FotoSql.QUERY_TYPE_DEFAULT);

            if (dbgFilter != null)  {
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + dbgFilter.toString());
            }
        }

        public boolean hasUserDefinedQuery() {
            return mHasUserDefinedQuery;
        }

        public void setHasUserDefinedQuery(boolean mHasUserDefinedQuery) {
            this.mHasUserDefinedQuery = mHasUserDefinedQuery;
        }
    }

    private GalleryQueryParameter mGalleryQueryParameter = new GalleryQueryParameter();
    // multi selection support
    private SelectedFotos mSelectedItems = null;

    private Queryable mGalleryGui;

    private boolean mHasEmbeddedDirPicker = false;
    private DirectoryGui mDirGui;

    private String mTitleResultCount = "";

    private IDirectory mDirectoryRoot = null;

    /** true if activity should show navigator dialog after loading mDirectoryRoot is complete */
    private boolean mMustShowNavigator = false;

    public static void showActivity(Activity context, GalleryFilterParameter filter, QueryParameter query, int requestCode) {
        Intent intent = new Intent(context, FotoGalleryActivity.class);

        if (filter != null) {
            intent.putExtra(EXTRA_FILTER, filter.toString());
        }

        if (query != null) {
            intent.putExtra(EXTRA_QUERY, query.toReParseableString());
        }

        if (requestCode != 0) {
            context.startActivityForResult(intent, requestCode);
        } else {
            context.startActivity(intent);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        this.mGalleryQueryParameter.saveInstanceState(this, savedInstanceState);
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState){
        Global.debugMemory(mDebugPrefix, "onCreate");
        super.onCreate(savedInstanceState);

        bookmarkController = new BookmarkController(this);

        this.getContentResolver().registerContentObserver(FotoSql.SQL_TABLE_EXTERNAL_CONTENT_URI, true, mMediaObserverDirectory);
        setContentView(R.layout.activity_gallery); // .gallery_activity);

        this.mGalleryQueryParameter.loadSettingsAndInstanceState(this, savedInstanceState);

        FragmentManager fragmentManager = getFragmentManager();
        mGalleryGui = (Queryable) fragmentManager.findFragmentById(R.id.galleryCursor);

        if (mGalleryGui instanceof GalleryCursorFragment) {
            this.mSelectedItems = ((GalleryCursorFragment) mGalleryGui).getSelectedItems();
        }

        // on tablet seperate dir navigator fragment
        mDirGui = (DirectoryGui) fragmentManager.findFragmentById(R.id.directoryFragment);

        if (FotoViewerParameter.galleryHasEmbeddedDirPicker) {
            if (mDirGui == null) {
                // on small screen/cellphone DirectoryGui is part of gallery
                mDirGui = (DirectoryGui) fragmentManager.findFragmentById(R.id.galleryCursor);
            } else {
                mHasEmbeddedDirPicker = true;
            }
        } else {
            if (mDirGui != null) {
                fragmentManager.beginTransaction().remove((Fragment) mDirGui).commit();
                mDirGui = null;
            }
        }

        setTitle();
        reloadGui("onCreate");
    }

    @Override
    protected void onPause () {
        Global.debugMemory(mDebugPrefix, "onPause");
        this.mGalleryQueryParameter.saveSettings(this);
        super.onPause();
    }

    @Override
    protected void onResume () {
        Global.debugMemory(mDebugPrefix, "onResume");
        super.onResume();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        invalidateDirectories(mDebugPrefix + "#onLowMemory");
    }

    @Override
    protected void onDestroy() {
        Global.debugMemory(mDebugPrefix, "onDestroy start");
        super.onDestroy();
        this.getContentResolver().unregisterContentObserver(mMediaObserverDirectory);

        // to avoid memory leaks
        GarbageCollector.freeMemory(findViewById(R.id.root_view));

        this.mGalleryQueryParameter.mGalleryContentQuery = null;
        mGalleryGui = null;
        mDirGui = null;
        invalidateDirectories(mDebugPrefix + "#onDestroy");

        System.gc();
        Global.debugMemory(mDebugPrefix, "onDestroy end");
        // RefWatcher refWatcher = AndroFotoFinderApp.getRefWatcher(this);
        // refWatcher.watch(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu items for use in the action bar
        MenuInflater inflater = getMenuInflater();

        inflater.inflate(R.menu.menu_gallery_non_selected_only, menu);
        inflater.inflate(R.menu.menu_gallery_non_multiselect, menu);
        /*
        getActionBar().setListNavigationCallbacks();
        MenuItem sorter = menu.getItem(R.id.cmd_sort);
        sorter.getSubMenu().
        */
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem sorter = menu.findItem(R.id.cmd_sort);

        if (sorter != null) {
            StringBuilder sortTitle = new StringBuilder();
            sortTitle.append(getString(R.string.sort_menu_title))
                    .append(": ")
                    .append(mGalleryQueryParameter.getSortDisplayName(this));
            sorter.setTitle(sortTitle.toString());
        }
        AboutDialogPreference.onPrepareOptionsMenu(this, menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle presses on the action bar items
        switch (item.getItemId()) {
            case R.id.cmd_select_folder:
                openFolderPicker();
                return true;

            case R.id.cmd_select_lat_lon:
                openLatLonPicker();
                return true;
            case R.id.cmd_filter:
                openFilter();
                return true;
            case R.id.cmd_load_bookmark:
                loadBookmark();
                return true;
            case R.id.cmd_sort:
                openSort();
                return true;
            case R.id.cmd_sort_date:
                this.mGalleryQueryParameter.setSortID(FotoSql.SORT_BY_DATE);
                reloadGui("sort date");
                return true;
            case R.id.cmd_sort_directory:
                this.mGalleryQueryParameter.setSortID(FotoSql.SORT_BY_NAME);
                reloadGui("sort dir");
                return true;
            case R.id.cmd_sort_len:
                this.mGalleryQueryParameter.setSortID(FotoSql.SORT_BY_NAME_LEN);
                reloadGui("sort len");
                return true;
            case R.id.cmd_sort_location:
                this.mGalleryQueryParameter.setSortID(FotoSql.SORT_BY_LOCATION);
                reloadGui("sort geo");
                return true;
            case R.id.cmd_settings:
                SettingsActivity.show(this);
                return true;
            case R.id.cmd_about:
                AboutDialogPreference.createAboutDialog(this).show();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }

    }

    private BookmarkController bookmarkController = null;

    private void loadBookmark() {
        bookmarkController.onLoadFromQuestion(new BookmarkController.IQueryConsumer() {
            @Override
            public void setQuery(QueryParameter newQuery) {
                final IGalleryFilter whereFilter = FotoSql.getWhereFilter(newQuery, true);
                mGalleryQueryParameter.mGalleryContentQuery = newQuery;
                mGalleryQueryParameter.setSortID(FotoSql.SORT_BY_NONE);
                onFilterChanged(whereFilter, "loadBookmark");
                invalidateDirectories(mDebugPrefix + "#loaded bookmark");
                mGalleryQueryParameter.setHasUserDefinedQuery(true);
                reloadGui("loaded bookmark");
            }
        }, this.mGalleryQueryParameter.calculateEffectiveGalleryContentQuery());
    }

    /**
     * Call back from sub-activities.<br/>
     * Process Change StartTime (longpress start), Select StopTime before stop
     * (longpress stop) or filter change for detailReport
     */
    @Override
    protected void onActivityResult(final int requestCode,
                                    final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        switch (requestCode) {
            case GalleryFilterActivity.resultID :
                onFilterChanged(GalleryFilterActivity.getFilter(intent), mDebugPrefix + "#onActivityResult from GalleryFilterActivity");
                break;
            case ImageDetailActivityViewPager.ACTIVITY_ID:
                if (resultCode == ImageDetailActivityViewPager.RESULT_CHANGE) {
                    invalidateDirectories(mDebugPrefix + "#onActivityResult from ImageDetailActivityViewPager");
                }
                break;
            case GeoEditActivity.RESULT_ID:
                if (resultCode == ImageDetailActivityViewPager.RESULT_CHANGE) {
                    invalidateDirectories(mDebugPrefix + "#onActivityResult from GeoEditActivity");
                }
                break;
        }
    }

    private void onFilterChanged(IGalleryFilter filter, String why) {
        if (filter != null) {
            this.mGalleryQueryParameter.mFilter = filter;
            this.mGalleryQueryParameter.setHasUserDefinedQuery(false);

            invalidateDirectories(mDebugPrefix + "#filter changed " + why);

            reloadGui("filter changed");
        }
    }

    private void openLatLonPicker() {
        mGalleryQueryParameter.mUseLatLon = true;

        MapGeoPickerActivity.showActivity(this, null, null, mSelectedItems, null, 0);

        /*
        final FragmentManager manager = getFragmentManager();
        LocationMapFragment dialog = new LocationMapFragment();
        dialog.defineNavigation(this.mGalleryQueryParameter.mFilter,
                this.mGalleryQueryParameter.mCurrentLatLon, ZoomUtil.NO_ZOOM, mSelectedItems);

        dialog.show(manager, DLG_NAVIGATOR_TAG);
        */
    }


    private void openFolderPicker() {
        mGalleryQueryParameter.mUseLatLon = false;

        int dirQueryID = this.mGalleryQueryParameter.getDirQueryID();

        /** if wrong datatype was saved: gallery is not allowed for dirPicker */
        if (FotoSql.QUERY_TYPE_GALLERY == dirQueryID) {
            dirQueryID = FotoSql.QUERY_TYPE_GROUP_ALBUM;
        }

        if (mDirectoryRoot == null) {
            // not loaded yet. load directoryRoot in background
            final QueryParameter currentDirContentQuery = new QueryParameter(FotoSql.getQuery(dirQueryID));
            FotoSql.setWhereFilter(currentDirContentQuery, this.mGalleryQueryParameter.mFilter, this.mGalleryQueryParameter.getSortID() != FotoSql.SORT_BY_NONE);

            this.mGalleryQueryParameter.mDirQueryID = (currentDirContentQuery != null) ? currentDirContentQuery.getID() : FotoSql.QUERY_TYPE_UNDEFINED;

            if (currentDirContentQuery != null) {
                this.mMustShowNavigator = true;
                DirectoryLoaderTask loader = new DirectoryLoaderTask(this, mDebugPrefix) {
                    @Override
                    protected void onPostExecute(IDirectory directoryRoot) {
                        onDirectoryDataLoadComplete(directoryRoot);
                    }
                };
                loader.execute(currentDirContentQuery);
            } else {
                Log.e(Global.LOG_CONTEXT, mDebugPrefix + " this.mDirQueryID undefined " + this.mGalleryQueryParameter.mDirQueryID);
            }
        } else {
            this.mMustShowNavigator = false;
            final FragmentManager manager = getFragmentManager();
            DirectoryPickerFragment dirDialog = new DirectoryPickerFragment(); // (DirectoryPickerFragment) manager.findFragmentByTag(DLG_NAVIGATOR_TAG);
            dirDialog.setContextMenuId(R.menu.menu_context_dirpicker);

            dirDialog.defineDirectoryNavigation(mDirectoryRoot, dirQueryID, this.mGalleryQueryParameter.mCurrentPath);

            mDirPicker = dirDialog;
            dirDialog.show(manager, DLG_NAVIGATOR_TAG);
        }
    }

    private void openFilter() {
        GalleryFilterActivity.showActivity(this, this.mGalleryQueryParameter.mFilter, this.mGalleryQueryParameter.mGalleryContentQuery);
    }

    private void openSort() {
    }

    /** called by Fragment: a fragment Item was clicked */
    @Override
    public void onGalleryImageClick(long imageId, Uri imageUri, int position) {
        Global.debugMemory(mDebugPrefix, "onGalleryImageClick");
        QueryParameter imageDetailQuery = this.mGalleryQueryParameter.calculateEffectiveGalleryContentQuery();
        ImageDetailActivityViewPager.showActivity(this, imageUri, position, imageDetailQuery);
    }

    /** GalleryFragment tells the Owning Activity that querying data has finisched */
    @Override
    public void setResultCount(int count) {
        this.mTitleResultCount = (count > 0) ? ("(" + count + ")") : "";
        setTitle();

        // current path does not contain photo => reload witout current path
        if ((count == 0) &&(mGalleryQueryParameter.clearPathIfActive())) {
            setTitle();
            reloadGui("query changed");
        }
    }

    /**
     * called when user selects a new directoryRoot
     *
     * @param selectedAbsolutePath
     * @param queryTypeId
     */
    @Override
    public void onDirectoryPick(String selectedAbsolutePath, int queryTypeId) {
        if (!this.mHasEmbeddedDirPicker) {
            navigateTo(selectedAbsolutePath, queryTypeId);
        }
        mDirPicker = null;
    }

    @Override
    public void invalidateDirectories(String why) {

        if (mDirectoryRoot != null) {
            if (Global.debugEnabled) {
                StringBuilder name = new StringBuilder(mDirectoryRoot.getAbsolute());
                Directory.appendCount(name, mDirectoryRoot, Directory.OPT_DIR | Directory.OPT_SUB_DIR);
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + "invalidateDirectories(" + name + ") because of " + why);
            }
            if (mDirPicker == null) {
                mDirectoryRoot.destroy();
                mDirectoryRoot = null; // must reload next time
            }
        }
    }

    /**
     * called when user cancels selection of a new directoryRoot
     * @param queryTypeId
     */
    @Override
    public void onDirectoryCancel(int queryTypeId) {
        mDirPicker = null;
    }

    /** called after the selection in tree has changed */
    @Override
    public void onDirectorySelectionChanged(String selectedAbsolutePath, int queryTypeId) {
        if (this.mHasEmbeddedDirPicker) {
            navigateTo(selectedAbsolutePath, queryTypeId);
        }
    }

    private void navigateTo(String selectedAbsolutePath, int queryTypeId) {

        if (selectedAbsolutePath != null) {
            if (mGalleryQueryParameter.mUseLatLon) {
                Log.d(Global.LOG_CONTEXT, "FotoGalleryActivity.navigateTo " + selectedAbsolutePath + " from " + mGalleryQueryParameter.mCurrentLatLon);
                this.mGalleryQueryParameter.mCurrentLatLon.get(DirectoryFormatter.parseLatLon(selectedAbsolutePath));

                reloadGui("navigate to geo");
            } else { //  if (this.mGalleryQueryParameter.mCurrentPath.compareTo(selectedAbsolutePath) != 0) {
                Log.d(Global.LOG_CONTEXT, "FotoGalleryActivity.navigateTo " + selectedAbsolutePath + " from " + this.mGalleryQueryParameter.mCurrentPath);
                this.mGalleryQueryParameter.mCurrentPath = selectedAbsolutePath;
                this.mGalleryQueryParameter.mDirQueryID = queryTypeId;
                setTitle();

                reloadGui("navigate to dir");
            }
        }
    }

    private void reloadGui(String why) {
        if (mGalleryGui != null) {
            QueryParameter query = this.mGalleryQueryParameter.calculateEffectiveGalleryContentQuery();
            if (query != null) {
                this.mGalleryGui.requery(this, query, mDebugPrefix + why);
            }
        }

        if (mDirGui != null) {
            String currentPath = this.mGalleryQueryParameter.mCurrentPath;
            if (currentPath != null) {
                mDirGui.navigateTo(currentPath);
            }
        }
    }

    private void onDirectoryDataLoadComplete(IDirectory directoryRoot) {
        if (directoryRoot == null) {
            final String message = getString(R.string.folder_err_load_failed_format, FotoSql.getName(this, this.mGalleryQueryParameter.getDirQueryID()));
            Toast.makeText(this, message,Toast.LENGTH_LONG).show();
        } else {
            mDirectoryRoot = directoryRoot;
            final boolean mustDefineNavigation = (mDirGui != null) && (this.mGalleryQueryParameter.mCurrentPath != null);
            final boolean mustShowFolderPicker = (mDirectoryRoot != null) && (this.mMustShowNavigator);

            if (Global.debugEnabled) {
                StringBuilder name = new StringBuilder(mDirectoryRoot.getAbsolute());
                Directory.appendCount(name, mDirectoryRoot, Directory.OPT_DIR | Directory.OPT_SUB_DIR);
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + "onDirectoryDataLoadComplete(" +
                        "mustDefineNavigation=" + mustDefineNavigation +
                        ", mustShowFolderPicker=" + mustShowFolderPicker +
                        ", content=" + name + ")");
            }

            if (mustDefineNavigation) {
                mDirGui.defineDirectoryNavigation(directoryRoot, this.mGalleryQueryParameter.getDirQueryID(), this.mGalleryQueryParameter.mCurrentPath);
            }
            Global.debugMemory(mDebugPrefix, "onDirectoryDataLoadComplete");

            if (mustShowFolderPicker) {
                openFolderPicker();
            }
        }
    }

    private void setTitle() {
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        if (title == null) {
            if (mGalleryQueryParameter.mUseLatLon) {
                title = getString(R.string.gallery_title);
            } else if (this.mGalleryQueryParameter.mCurrentPath != null) {
                title = FotoSql.getName(this, this.mGalleryQueryParameter.getDirQueryID())
                        + " - " + this.mGalleryQueryParameter.mCurrentPath;
            } else {
                title = FotoSql.getName(this, this.mGalleryQueryParameter.getDirQueryID());
            }
        }
        if (title != null) {
            this.setTitle(title + mTitleResultCount);
        }
    }
}
