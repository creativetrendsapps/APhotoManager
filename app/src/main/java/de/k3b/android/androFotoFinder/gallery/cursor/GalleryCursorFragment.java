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
 
package de.k3b.android.androFotoFinder.gallery.cursor;

import android.app.Activity;
import android.app.LoaderManager;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.app.Fragment;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ShareActionProvider;
import android.widget.Toast;

// import com.squareup.leakcanary.RefWatcher;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.k3b.android.androFotoFinder.Common;
import de.k3b.android.androFotoFinder.FotoGalleryActivity;
import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.directory.DirectoryGui;
import de.k3b.android.androFotoFinder.directory.DirectoryPickerFragment;
import de.k3b.android.androFotoFinder.imagedetail.ImageDetailActivityViewPager;
import de.k3b.android.androFotoFinder.imagedetail.ImageDetailDialogBuilder;
import de.k3b.android.androFotoFinder.locationmap.GeoEditActivity;
import de.k3b.android.androFotoFinder.locationmap.MapGeoPickerActivity;
import de.k3b.android.androFotoFinder.queries.FotoViewerParameter;
import de.k3b.android.androFotoFinder.queries.FotoSql;
import de.k3b.android.androFotoFinder.R;
import de.k3b.android.androFotoFinder.OnGalleryInteractionListener;
import de.k3b.android.androFotoFinder.queries.Queryable;
import de.k3b.android.androFotoFinder.queries.SqlJobTaskBase;
import de.k3b.android.util.AndroidFileCommands;
import de.k3b.android.util.MediaScanner;
import de.k3b.android.util.SelectedFotos;
import de.k3b.android.widget.Dialogs;
import de.k3b.database.QueryParameter;
import de.k3b.database.SelectedItems;
import de.k3b.io.Directory;
import de.k3b.io.IDirectory;
import de.k3b.io.OSDirectory;

/**
 * A {@link Fragment} to show ImageGallery content based on ContentProvider-Cursor.
 * Activities that contain this fragment must implement the
 * {@link OnGalleryInteractionListener} interface
 * to handle interaction events.
 * Use the {@link GalleryCursorFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class GalleryCursorFragment extends Fragment  implements Queryable, DirectoryGui,Common {
    private static final String INSTANCE_STATE_LAST_VISIBLE_POSITION = "lastVisiblePosition";
    private static final String INSTANCE_STATE_SELECTED_ITEM_IDS = "selectedItems";
    private static final String INSTANCE_STATE_OLD_TITLE = "oldTitle";
    private static final String INSTANCE_STATE_SEL_ONLY = "selectedOnly";
    private static final String INSTANCE_STATE_LOADER_ID = "loaderID";

    private static int nextLoaderID = 100;
    private int loaderID = -1;

    private HorizontalScrollView mParentPathBarScroller;
    private LinearLayout mParentPathBar;

    private HorizontalScrollView mChildPathBarScroller;
    private LinearLayout mChildPathBar;

    // for debugging
    private static int id = 1;
    private String mDebugPrefix;

    private GridView mGalleryView;

    private ShareActionProvider mShareActionProvider;
    private MenuItem mShareOnlyToggle;
    private GalleryCursorAdapter mAdapter = null;

    private OnGalleryInteractionListener mGalleryListener;
    private QueryParameter mGalleryContentQuery;

    private DirectoryPickerFragment.OnDirectoryInteractionListener mDirectoryListener;
    private int mLastVisiblePosition = -1;

    // multi selection support
    private final SelectedFotos mSelectedItems = new SelectedFotos();
    private String mOldTitle = null;
    private boolean mShowSelectedOnly = false;
    private final AndroidFileCommands mFileCommands = new LocalFileCommands();
    private MenuItem mMenuRemoveAllSelected = null;

    /* false: prevent showing error message again */
    private boolean mNoShareError = true;

    /**************** construction ******************/
    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of fragment GalleryCursorFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static GalleryCursorFragment newInstance() {
        GalleryCursorFragment fragment = new GalleryCursorFragment();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    public SelectedFotos getSelectedItems() {
        return mSelectedItems;
    }

    class LocalCursorLoader implements LoaderManager.LoaderCallbacks<Cursor> {
        /** incremented every time a new curster/query is generated */
        private int mRequeryInstanceCount = 0;

        /** called by LoaderManager.getLoader(ACTIVITY_ID) to (re)create loader
         * that attaches to last query/cursor if it still exist i.e. after rotation */
        @Override
        public Loader<Cursor> onCreateLoader(int aLoaderID, Bundle bundle) {
            if (loaderID == aLoaderID) {
                QueryParameter query = getCurrentQuery();
                mRequeryInstanceCount++;
                if (Global.debugEnabledSql) {
                    Log.i(Global.LOG_CONTEXT, mDebugPrefix + " onCreateLoader"
                            + getDebugContext() +
                            " : query = " + query);
                }
                return FotoSql.createCursorLoader(getActivity().getApplicationContext(), query);
            }

            // An invalid id was passed in
            return null;
        }

        /** called after media db content has changed */
        @Override
        public void onLoadFinished(Loader<Cursor> _loader, Cursor data) {
            mLastVisiblePosition = mGalleryView.getLastVisiblePosition();

            final Activity context = getActivity();
            if (data == null) {
                FotoSql.CursorLoaderWithException loader = (FotoSql.CursorLoaderWithException) _loader;
                String title;
                String message = context.getString(R.string.global_err_sql_message_format, loader.getException().getMessage(), loader.getQuery().toSqlString());
                if (loader.getException() != null) {
                    if (0 != loader.getQuery().toSqlString().compareTo(getCurrentQuery(FotoSql.queryDetail).toSqlString())) {
                        // query is not default query. revert to default query
                        mGalleryContentQuery = FotoSql.queryDetail;
                        requery("requery after query-errror");
                        title = context.getString(R.string.global_err_sql_title_reload);
                    } else {
                        title = context.getString(R.string.global_err_system);
                        context.finish();
                    }
                    Dialogs.messagebox(context, title, message);
                    return;
                }
            }

            // do change the data
            mAdapter.swapCursor(data);

            if (mLastVisiblePosition > 0) {
                mGalleryView.smoothScrollToPosition(mLastVisiblePosition);
                mLastVisiblePosition = -1;
            }

            final int resultCount = (data == null) ? 0 : data.getCount();
            if (Global.debugEnabled) {
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + " onLoadFinished"
                        + getDebugContext() +
                        " fount " + resultCount + " rows");
            }

            // do change the data
            mAdapter.notifyDataSetChanged();

            if (mLastVisiblePosition > 0) {
                mGalleryView.smoothScrollToPosition(mLastVisiblePosition);
                mLastVisiblePosition = -1;
            }

            // show the changes

            if (context instanceof OnGalleryInteractionListener) {
                ((OnGalleryInteractionListener) context).setResultCount(resultCount);;
            }
            multiSelectionReplaceTitleIfNecessary();
        }

        /** called by LoaderManager. after search criteria were changed or if activity is destroyed. */
        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            if (Global.debugEnabled) {
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + " onLoaderReset" + getDebugContext());
            }
            // rember position where we have to scroll to after reload is finished.
            mLastVisiblePosition = mGalleryView.getLastVisiblePosition();

            mAdapter.swapCursor(null);
            mAdapter.notifyDataSetChanged();
        }

        @NonNull
        String getDebugContext() {
            return "(@" + loaderID + ", #" + mRequeryInstanceCount +
                    ", LastVisiblePosition=" + mLastVisiblePosition +
//                    ",  Path='" + mInitialFilePath +
                    "')";
        }
    }

    LocalCursorLoader mCurorLoader = null;

    class LocalFileCommands extends AndroidFileCommands {

        @Override
        protected void onPostProcess(String what, String[] oldPathNames, String[] newPathNames, int modifyCount, int itemCount, int opCode) {
            Context context = getActivity().getApplicationContext();
            if (Global.clearSelectionAfterCommand || (opCode == OP_DELETE) || (opCode == OP_MOVE)) {
                mShowSelectedOnly = true;
                multiSelectionCancel();
            }

            super.onPostProcess(what, oldPathNames, newPathNames, modifyCount, itemCount, opCode);
        }
    }

    public GalleryCursorFragment() {
        mDebugPrefix = "GalleryCursorFragment#" + (id++)  + " ";
        Global.debugMemory(mDebugPrefix, "ctor");

        // Required empty public constructor
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, mDebugPrefix + "()");
        }
    }

    /**************** live-cycle ******************/
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Global.debugMemory(mDebugPrefix, "onCreate");
        setHasOptionsMenu(true);
        this.mShareActionProvider = new ShareActionProvider(this.getActivity());
        if (savedInstanceState != null) {
            this.mLastVisiblePosition = savedInstanceState.getInt(INSTANCE_STATE_LAST_VISIBLE_POSITION, this.mLastVisiblePosition);
            this.loaderID = savedInstanceState.getInt(INSTANCE_STATE_LOADER_ID, this.loaderID);
            String old = mSelectedItems.toString();
            mSelectedItems.clear();
            mSelectedItems.parse(savedInstanceState.getString(INSTANCE_STATE_SELECTED_ITEM_IDS, old));
            this.mOldTitle = savedInstanceState.getString(INSTANCE_STATE_OLD_TITLE, this.mOldTitle);
            this.mShowSelectedOnly = savedInstanceState.getBoolean(INSTANCE_STATE_SEL_ONLY, this.mShowSelectedOnly);
            if (!mSelectedItems.isEmpty()) {
                mMustReplaceMenue = true;
                getActivity().invalidateOptionsMenu();
            }
        } else {
            // first creation of new instance
            loaderID = nextLoaderID++;
        }
        if (mDebugPrefix.indexOf("@") < 0) {
            mDebugPrefix += "@" + loaderID + " ";
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mLastVisiblePosition = mGalleryView.getLastVisiblePosition();
        outState.putInt(INSTANCE_STATE_LAST_VISIBLE_POSITION, mLastVisiblePosition);
        outState.putInt(INSTANCE_STATE_LOADER_ID, loaderID);
        outState.putString(INSTANCE_STATE_SELECTED_ITEM_IDS, this.mSelectedItems.toString());
        outState.putString(INSTANCE_STATE_OLD_TITLE, this.mOldTitle);
        outState.putBoolean(INSTANCE_STATE_SEL_ONLY, this.mShowSelectedOnly);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Global.debugMemory(mDebugPrefix, "onCreateView");

        // Inflate the layout for this fragment
        View result = inflater.inflate(R.layout.fragment_gallery, container, false);
        mGalleryView = (GridView) result.findViewById(R.id.gridView);

        mAdapter = new GalleryCursorAdapter(this.getActivity(), mSelectedItems, mDebugPrefix);
        mGalleryView.setAdapter(mAdapter);

        mGalleryView.setLongClickable(true);
        mGalleryView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View v, int position, long id) {
                return onGalleryLongImageClick((GalleryCursorAdapter.GridCellViewHolder) v.getTag(), position);
            }
        });
        mShareActionProvider.setOnShareTargetSelectedListener(new ShareActionProvider.OnShareTargetSelectedListener() {
            @Override
            public boolean onShareTargetSelected(ShareActionProvider source, Intent intent) {
                if (Global.clearSelectionAfterCommand) {
                    multiSelectionCancel();
                }
                return false;
            }
        });

        mGalleryView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                onGalleryImageClick((GalleryCursorAdapter.GridCellViewHolder) v.getTag(), position);
            }
        });

        this.mParentPathBar = (LinearLayout) result.findViewById(R.id.parent_owner);
        this.mParentPathBarScroller = (HorizontalScrollView) result.findViewById(R.id.parent_scroller);

        this.mChildPathBar = (LinearLayout) result.findViewById(R.id.child_owner);
        this.mChildPathBarScroller = (HorizontalScrollView) result.findViewById(R.id.child_scroller);

        reloadDirGuiIfAvailable("onCreateView");

        fixMediaDatabase();

        requery("onCreateView");
        return result;
    }

    @Override
    public void onAttach(Activity activity) {
        Global.debugMemory(mDebugPrefix, "onAttach");
        super.onAttach(activity);
        mFileCommands.setContext(activity);
        mFileCommands.setLogFilePath(mFileCommands.getDefaultLogFile());
        MoveOrCopyDestDirPicker.sFileCommands = mFileCommands;
        try {
            mGalleryListener = (OnGalleryInteractionListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnGalleryInteractionListener");
        }

        try {
            mDirectoryListener = (DirectoryPickerFragment.OnDirectoryInteractionListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement DirectoryPickerFragment.OnDirectoryInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        Global.debugMemory(mDebugPrefix, "onDetach");
        super.onDetach();
        mGalleryListener = null;
        mDirectoryListener = null;
        mFileCommands.setContext(null);
        MoveOrCopyDestDirPicker.sFileCommands = null;
    }

    @Override
    public void onPause() {
        super.onPause();
        destroyLoaderIfFinishing("onPause");
    }

    @Override
    public void onDestroy() {
        Global.debugMemory(mDebugPrefix, "before onDestroy");

        destroyLoaderIfFinishing("onDestroy");

        mFileCommands.closeLogFile();
        mFileCommands.closeAll();
        mGalleryContentQuery = null;
        mAdapter = null;
        super.onDestroy();
        System.gc();
        Global.debugMemory(mDebugPrefix, "after onDestroy");
        // RefWatcher refWatcher = AndroFotoFinderApp.getRefWatcher(getActivity());
        // refWatcher.watch(this);
        super.onDestroy();
    }

    private void destroyLoaderIfFinishing(String context) {
        if ((loaderID != -1) && (getActivity() != null) && (getActivity().isFinishing())) {
            getLoaderManager().destroyLoader(loaderID);
            if ((Global.debugEnabled) && (mCurorLoader != null)) {
                Log.d(Global.LOG_CONTEXT, mDebugPrefix + context + " - releasing mCurorLoader" +
                        mCurorLoader.getDebugContext());
            }
            mCurorLoader = null;
            if (loaderID == (nextLoaderID - 1)) nextLoaderID--;
            loaderID = -1;
        }
    }

    /**
     * interface Queryable: Owning activity tells fragment to change its content:
     * Initiates a database requery in the background
     *  @param context
     * @param parameters
     * @param why
     */
    @Override
    public void requery(Activity context, QueryParameter parameters, String why) {
        this.mGalleryContentQuery = parameters;

        requery(why);
    }

    private void requery(String why) {
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, mDebugPrefix + why + " requery " + ((mGalleryContentQuery != null) ? mGalleryContentQuery.toSqlString() : null));
        }

        if (mGalleryContentQuery != null) {
            // query has been initialized
            if (mCurorLoader == null) {
                mCurorLoader = new LocalCursorLoader();
                getLoaderManager().initLoader(loaderID, null, mCurorLoader);
            } else {
                getLoaderManager().restartLoader(loaderID, null, this.mCurorLoader);
            }
        }
    }

    private QueryParameter getCurrentQuery() {
        return getCurrentQuery(mGalleryContentQuery);
    }

    private QueryParameter getCurrentQuery(QueryParameter rootQuery) {
        QueryParameter selFilter = null;
        if (mShowSelectedOnly) {
            selFilter = new QueryParameter(rootQuery);
            FotoSql.setWhereSelection(selFilter, mSelectedItems);
        } else {
            selFilter = rootQuery;
        }
        return selFilter;
    }

    @Override
    public String toString() {
        return mDebugPrefix + this.mAdapter;
    }

    /*********************** local helper *******************************************/
    /** an Image in the FotoGallery was clicked */
    private void onGalleryImageClick(final GalleryCursorAdapter.GridCellViewHolder holder, int position) {
        if ((!multiSelectionHandleClick(holder)) && (mGalleryListener != null)) {
            if (holder.filter != null) {
                QueryParameter subGalleryQuery = new QueryParameter(FotoSql.queryDetail);

                subGalleryQuery.addWhere(holder.filter);
                onOpenChildGallery(subGalleryQuery);
                return;
            }
            long imageID = holder.imageID;
            mGalleryListener.onGalleryImageClick(imageID, SelectedFotos.getUri(imageID), position);
        }
    }

    private void onOpenChildGallery(QueryParameter subGalleryQuery) {
        if (Global.debugEnabledSql) {
            Log.i(Global.LOG_CONTEXT, "Exec child gallery\n\t" + subGalleryQuery.toSqlString());
        }
        FotoGalleryActivity.showActivity(getActivity(), null, subGalleryQuery, 0);
    }

    /****************** path navigation *************************/

    private IDirectory mDirectoryRoot = null;
    private int mDirQueryID = 0;

    private String mCurrentPath = null;

    /** Defines Directory Navigation */
    @Override
    public void defineDirectoryNavigation(IDirectory root, int dirTypId, String initialAbsolutePath) {
        mDirectoryRoot = root;
        mDirQueryID = dirTypId;
        navigateTo(initialAbsolutePath);

    }

    /** Set curent selection to absolutePath */
    @Override
    public void navigateTo(String absolutePath) {
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, mDebugPrefix + " navigateTo : " + absolutePath);
        }

        mCurrentPath = absolutePath;
        reloadDirGuiIfAvailable("navigateTo " + absolutePath);
        // requeryGallery(); done by owning activity
    }

    private void reloadDirGuiIfAvailable(String why) {
        if ((mDirectoryRoot != null) && (mCurrentPath != null) && (mParentPathBar != null)) {
            if (Global.debugEnabled) {
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + " reloadDirGuiIfAvailable : " + why);
            }

            mParentPathBar.removeAllViews();
            mChildPathBar.removeAllViews();

            IDirectory selectedChild = mDirectoryRoot.find(mCurrentPath);
            if (selectedChild == null) selectedChild = mDirectoryRoot;

            Button first = null;
            IDirectory current = selectedChild;
            while (current.getParent() != null) {
                Button button = createPathButton(current);
                // add parent left to chlild
                // gui order root/../child.parent/child
                mParentPathBar.addView(button, 0);
                if (first == null) first = button;
                current = current.getParent();
            }

            // scroll to right where deepest child is
            if (first != null) mParentPathBarScroller.requestChildFocus(mParentPathBar, first);

            List<IDirectory> children = selectedChild.getChildren();
            if (children != null) {
                for (IDirectory child : children) {
                    Button button = createPathButton(child);
                    mChildPathBar.addView(button);
                }
            }
        }
    }

    private Button createPathButton(IDirectory currentDir) {
        Button result = new Button(getActivity());
        result.setTag(currentDir);
        result.setText(getDirectoryDisplayText(null, currentDir, (FotoViewerParameter.includeSubItems) ? Directory.OPT_SUB_ITEM : Directory.OPT_ITEM));

        result.setOnClickListener(onPathButtonClickListener);
        return result;
    }

    /** path/directory was clicked */
    private View.OnClickListener onPathButtonClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            onPathButtonClick((IDirectory) v.getTag());
        }
    };

    /** path/directory was clicked */
    private void onPathButtonClick(IDirectory newSelection) {
        if ((mDirectoryListener != null) && (newSelection != null)) {
            mCurrentPath = newSelection.getAbsolute();
            mDirectoryListener.onDirectoryPick(mCurrentPath, this.mDirQueryID);
        }
    }

    /** getFrom tree display text */
    private static String getDirectoryDisplayText(String prefix, IDirectory directory, int options) {
        StringBuilder result = new StringBuilder();
        if (prefix != null) result.append(prefix);
        result.append(directory.getRelPath()).append(" ");
        Directory.appendCount(result, directory, options);
        return result.toString();
    }


    /********************** Multi selection support ***********************************************************/
    private boolean mMustReplaceMenue = false;

    /** starts mutliselection */
    private boolean onGalleryLongImageClick(final GalleryCursorAdapter.GridCellViewHolder holder, int position) {
        if (mSelectedItems.isEmpty()) {
            startMultiSelectionMode();

            mSelectedItems.add(holder.imageID);
            holder.icon.setVisibility(View.VISIBLE);
            multiSelectionUpdateActionbar("Start multisel");
        } else {
            // in gallery mode long click is view image
            ImageDetailActivityViewPager.showActivity(this.getActivity(), SelectedFotos.getUri(holder.imageID), position, getCurrentQuery());
        }
        return true;
    }

    private void startMultiSelectionMode() {
        // multi selection not active yet: start multi selection
        mOldTitle = getActivity().getTitle().toString();
        mMustReplaceMenue = true;
        mShowSelectedOnly = false;
        getActivity().invalidateOptionsMenu();
    }

    /** return true if multiselection is active */
    private boolean multiSelectionHandleClick(GalleryCursorAdapter.GridCellViewHolder holder) {
        if (!mSelectedItems.isEmpty()) {
            long imageID = holder.imageID;
            holder.icon.setVisibility((mSelectedItems.toggle(imageID)) ? View.VISIBLE : View.GONE);
            multiSelectionUpdateActionbar("changed mutli sel");
            return true;
        }
        multiSelectionUpdateActionbar("lost multi sel");
        return false;
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        if (mMustReplaceMenue) {
            mMustReplaceMenue = false;
            menu.clear();
            MenuInflater inflater = getActivity().getMenuInflater();
            inflater.inflate(R.menu.menu_gallery_multiselect_mode_all, menu);
            mShareOnlyToggle = menu.findItem(R.id.cmd_selected_only);
            if (mShowSelectedOnly) {
                mShareOnlyToggle.setIcon(android.R.drawable.checkbox_on_background);
                mShareOnlyToggle.setChecked(true);
            } else {
                inflater.inflate(R.menu.menu_gallery_non_selected_only, menu);
            }

            MenuItem shareItem = menu.findItem(R.id.menu_item_share);
            shareItem.setActionProvider(mShareActionProvider);
            inflater.inflate(R.menu.menu_image_commands, menu);

            multiSelectionUpdateShareIntent();
        }

        mMenuRemoveAllSelected = menu.findItem(R.id.cmd_selection_remove_all);

        updateSelectionCount();
    }

    protected void updateSelectionCount() {
        if (mMenuRemoveAllSelected != null) {
            mMenuRemoveAllSelected.setVisible(!mSelectedItems.isEmpty());
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        // Handle menuItem selection
        AndroidFileCommands fileCommands = mFileCommands;
        if ((mSelectedItems != null) && (fileCommands.onOptionsItemSelected(menuItem, mSelectedItems))) {
            return true;
        }
        switch (menuItem.getItemId()) {
            case R.id.cmd_cancel:
                return multiSelectionCancel();
            case R.id.cmd_selected_only:
                return multiSelectionToggle();
            case R.id.cmd_copy:
                return cmdMoveOrCopyWithDestDirPicker(false, fileCommands.getLastCopyToPath(), mSelectedItems);
            case R.id.cmd_move:
                return cmdMoveOrCopyWithDestDirPicker(true, fileCommands.getLastCopyToPath(), mSelectedItems);
            case R.id.cmd_show_geo:
                MapGeoPickerActivity.showActivity(this.getActivity(), null, null, mSelectedItems, null, 0);
                return true;
            case R.id.cmd_edit_geo:
                GeoEditActivity.showActivity(this.getActivity(), mSelectedItems);
                return true;
            case R.id.cmd_selection_add_all:
                addAllToSelection();
                return true;
            case R.id.cmd_selection_remove_all:
                removeAllFromSelection();
                return true;

            case R.id.action_details:
                cmdShowDetails();
                return true;
            case R.id.cmd_scan:
                return fileCommands.cmdMediaScannerWithQuestion();

            default:
                return super.onOptionsItemSelected(menuItem);
        }

    }

    private void cmdShowDetails() {
        ImageDetailDialogBuilder.createImageDetailDialog(this.getActivity(), getActivity().getTitle().toString(),
                this.toString(), mGalleryContentQuery.toSqlString()).show();
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
            dismiss();
            sFileCommands.onMoveOrCopyDirectoryPick(getMove(), selection, getSrcFotos());
        }
    };

    private boolean cmdMoveOrCopyWithDestDirPicker(final boolean move, String lastCopyToPath, final SelectedFotos fotos) {
        if (AndroidFileCommands.canProcessFile(this.getActivity())) {
            MoveOrCopyDestDirPicker destDir = MoveOrCopyDestDirPicker.newInstance(move, fotos);

            destDir.defineDirectoryNavigation(new OSDirectory("/", null),
                    (move) ? FotoSql.QUERY_TYPE_GROUP_MOVE : FotoSql.QUERY_TYPE_GROUP_COPY,
                    lastCopyToPath);
            destDir.setContextMenuId(R.menu.menu_context_osdir);
            destDir.show(getActivity().getFragmentManager(), "osdir");
        }
        return false;
    }

    private boolean multiSelectionToggle() {
        mShowSelectedOnly = !mShowSelectedOnly;
        mMustReplaceMenue = true;
        getActivity().invalidateOptionsMenu();

        requery("multiSelectionToggle");
        return true;
    }

    private boolean multiSelectionCancel() {
        mSelectedItems.clear();

        for (int i = mGalleryView.getChildCount() - 1; i >= 0; i--)
        {
            GalleryCursorAdapter.GridCellViewHolder holder =  (GalleryCursorAdapter.GridCellViewHolder) mGalleryView.getChildAt(i).getTag();
            if (holder != null) {
                holder.icon.setVisibility(View.GONE);
            }
        }
        multiSelectionUpdateActionbar("multiSelectionCancel");
        return true;
    }

    void multiSelectionReplaceTitleIfNecessary() {
        if (!mSelectedItems.isEmpty()) {
            mOldTitle = getActivity().getTitle().toString();
            multiSelectionUpdateActionbar("selection my have changed");
        }
    }

    private void multiSelectionUpdateActionbar(String why) {
        String newTitle = null;
        if (mSelectedItems.isEmpty()) {

            // lost last selection. revert mShowSelectedOnly if neccessary
            if (mShowSelectedOnly) {
                mShowSelectedOnly = false;
                requery(why + "-lost multisel");
            }

            // lost last selection. revert title if neccessary
            if (mOldTitle != null) {
                // last is deselected. Restore title and menu;
                newTitle = mOldTitle;
                mOldTitle = null;
                getActivity().invalidateOptionsMenu();
            }
        } else {
            // multi selection is active: update title and data for share menue
            newTitle = getActivity().getString(R.string.selection_status_format, mSelectedItems.size());
            multiSelectionUpdateShareIntent();
        }

        if (newTitle != null) {
            getActivity().setTitle(newTitle);
        }
        updateSelectionCount();
    }

    private void multiSelectionUpdateShareIntent() {
        int selectionCount = mSelectedItems.size();
        if ((selectionCount > 0) && (mShareActionProvider != null)) {
            Intent sendIntent = new Intent();
            sendIntent.setType("image/*");
            if (selectionCount == 1) {
                Long imageId = mSelectedItems.first();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(EXTRA_STREAM, SelectedFotos.getUri(imageId));
            } else {
                sendIntent.setAction(Intent.ACTION_SEND_MULTIPLE);

                ArrayList<Uri> uris = new ArrayList<Uri>();

                Iterator<Long> iter = mSelectedItems.iterator();
                while (iter.hasNext()) {
                    uris.add(SelectedFotos.getUri(iter.next()));
                }
                sendIntent.putParcelableArrayListExtra(EXTRA_STREAM, uris);
            }

            try {
                mShareActionProvider.setShareIntent(sendIntent);
                this.mNoShareError = true;
            } catch (Exception e) {
                if (this.mNoShareError) {
                    Toast.makeText(this.getActivity(), R.string.share_err_to_many, Toast.LENGTH_LONG).show();
                    this.mNoShareError = false; // do not show it again
                }
            }
        }

    }
    private void removeAllFromSelection() {
        SqlJobTaskBase task = new SqlJobTaskBase(this.getActivity(), "removeAllFromSelection", this.mSelectedItems) {
            @Override
            protected void doInBackground(Long id, Cursor cursor) {
                this.mSelectedItems.remove(id);
            }

            @Override
            protected void onPostExecute(SelectedItems selectedItems) {
                replaceSelectedItems(selectedItems, mStatus, mDebugPrefix);
            }
        };
        QueryParameter query = getCurrentQuery();
        task.execute(query);
    }

    private void addAllToSelection() {
        SqlJobTaskBase task = new SqlJobTaskBase(this.getActivity(), "addAllToSelection", this.mSelectedItems) {
            @Override
            protected void doInBackground(Long id, Cursor cursor) {
                this.mSelectedItems.add(id);
            }

            @Override
            protected void onPostExecute(SelectedItems selectedItems) {
                replaceSelectedItems(selectedItems, mStatus, mDebugPrefix);
            }
        };
        QueryParameter query = getCurrentQuery();
        task.execute(query);
    }

    private void replaceSelectedItems(SelectedItems selectedItems, StringBuffer debugMessage, String why) {
        int oldSize = mSelectedItems.size();
        this.mSelectedItems.clear();
        this.mSelectedItems.addAll(selectedItems);
        int newSize = mSelectedItems.size();

        if (debugMessage != null) {
            debugMessage.append("\nSelections ").append(oldSize).append("=>").append(newSize);
            Log.i(Global.LOG_CONTEXT, debugMessage.toString());
        }
        if ((oldSize == 0) && (newSize > 0)) {
            startMultiSelectionMode();
        }
        multiSelectionUpdateActionbar(why);
        requery(why);
    }

    //-------------------------------------------------------------

    private void fixMediaDatabase() {
        if (!MediaScanner.isScannerActive(getActivity().getContentResolver())) {
            if (Global.debugEnabled) {
                Log.d(Global.LOG_CONTEXT, "Analysing media database for potential problems");
            }
            repairMissingDisplayNames();
            removeDuplicates();
        }
    }

    /** image entries may not have DISPLAY_NAME which is essential for calculating the item-s folder. */
    private void repairMissingDisplayNames() {
        SqlJobTaskBase task = new SqlJobTaskBase(this.getActivity(), "Searching media database for missing 'displayname'-s:\n", null) {
            private int mPathColNo = -2;
            private int mResultCount = 0;

            @Override
            protected void doInBackground(Long id, Cursor cursor) {
                if (mPathColNo == -2) mPathColNo = cursor.getColumnIndex(FotoSql.SQL_COL_PATH);
                MediaScanner.updatePathRelatedFields(getActivity(), cursor, cursor.getString(mPathColNo), mColumnIndexPK, mPathColNo);

                mResultCount++;
            }

            @Override
            protected void onPostExecute(SelectedItems selectedItems) {
                if (!isCancelled()) {
                    onMissingDisplayNamesComplete(mResultCount, mStatus);
                }
            }
        };
        QueryParameter query = FotoSql.queryGetMissingDisplayNames;
        task.execute(query);
    }

    /** called after MissingDisplayNamesComplete finished */
    private void onMissingDisplayNamesComplete(int mResultCount, StringBuffer debugMessage) {
        if (debugMessage != null) {
            Log.w(Global.LOG_CONTEXT, mDebugPrefix + debugMessage);
        }

        if (mResultCount > 0) {
        }
    }

    private void removeDuplicates() {
        SqlJobTaskBase task = new SqlJobTaskBase(this.getActivity(), "Searching for duplcates in media database:\n", null) {
            @Override
            protected void doInBackground(Long id, Cursor cursor) {
                this.mSelectedItems.add(id);
            }

            @Override
            protected void onPostExecute(SelectedItems selectedItems) {
                if (!isCancelled()) {
                    if ((selectedItems != null) && (selectedItems.size() > 0)) {
                        onDuplicatesFound(selectedItems, mStatus);
                    } else {
                        onDuplicatesFound(null, mStatus);
                    }
                }
            }
        };
        QueryParameter query = FotoSql.queryGetDuplicates;
        task.execute(query);
    }

    /** is called when removeDuplicates() found duplicates */
    private void onDuplicatesFound(SelectedItems selectedItems, StringBuffer debugMessage) {
        if (debugMessage != null) {
            Log.w(Global.LOG_CONTEXT, mDebugPrefix + debugMessage);
        }

        if (selectedItems != null) {
            QueryParameter query = new QueryParameter();
            FotoSql.setWhereSelection(query, selectedItems);

            final Activity activity = getActivity();

            // might be null in in orientation change
            if (activity != null) {
                int delCount = activity.getContentResolver().delete(FotoSql.SQL_TABLE_EXTERNAL_CONTENT_URI, query.toAndroidWhere(), null);
                if (debugMessage != null) {
                    Log.w(Global.LOG_CONTEXT, mDebugPrefix + " deleted " + delCount +
                            " duplicates\n\tDELETE ... WHERE " + query.toAndroidWhere());
                }

                if (delCount > 0) {
                    requery("after delete duplicates"); // content has changed: must reload
                }
            }
        }
    }
}
