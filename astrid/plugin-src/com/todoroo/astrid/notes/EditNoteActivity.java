/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.notes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.support.v4.app.Fragment;
import android.text.Editable;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.timsu.astrid.R;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.sql.Join;
import com.todoroo.andlib.sql.Order;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.actfm.ActFmCameraModule;
import com.todoroo.astrid.actfm.ActFmCameraModule.CameraResultCallback;
import com.todoroo.astrid.actfm.ActFmCameraModule.ClearImageCallback;
import com.todoroo.astrid.actfm.sync.ActFmPreferenceService;
import com.todoroo.astrid.actfm.sync.ActFmSyncService;
import com.todoroo.astrid.actfm.sync.ActFmSyncThread;
import com.todoroo.astrid.actfm.sync.messages.BriefMe;
import com.todoroo.astrid.activity.AstridActivity;
import com.todoroo.astrid.activity.TaskEditFragment;
import com.todoroo.astrid.adapter.UpdateAdapter;
import com.todoroo.astrid.core.PluginServices;
import com.todoroo.astrid.dao.MetadataDao.MetadataCriteria;
import com.todoroo.astrid.dao.UserActivityDao;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.RemoteModel;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.data.User;
import com.todoroo.astrid.data.UserActivity;
import com.todoroo.astrid.helper.AsyncImageView;
import com.todoroo.astrid.helper.ImageDiskCache;
import com.todoroo.astrid.service.MetadataService;
import com.todoroo.astrid.service.StartupService;
import com.todoroo.astrid.service.StatisticsConstants;
import com.todoroo.astrid.service.StatisticsService;
import com.todoroo.astrid.sync.SyncResultCallback;
import com.todoroo.astrid.timers.TimerActionControlSet.TimerActionListener;

public class EditNoteActivity extends LinearLayout implements TimerActionListener {



    public static final String EXTRA_TASK_ID = "task"; //$NON-NLS-1$
    private static final String LAST_FETCH_KEY = "task-fetch-"; //$NON-NLS-1$

    private Task task;

    @Autowired ActFmSyncService actFmSyncService;
    @Autowired ActFmPreferenceService actFmPreferenceService;
    @Autowired MetadataService metadataService;
    @Autowired UserActivityDao userActivityDao;

    private final ArrayList<NoteOrUpdate> items = new ArrayList<NoteOrUpdate>();
    private EditText commentField;
    private TextView loadingText;
    private final View commentsBar;
    private final View parentView;
    private View timerView;
    private View commentButton;
    private int commentItems = 10;
    private ImageButton pictureButton;
    private Bitmap pendingCommentPicture = null;
    private final Fragment fragment;
    private final AstridActivity activity;
    private final ImageDiskCache imageCache;
    private final int cameraButton;
    private final String linkColor;

    private static boolean respondToPicture = false;

    private final List<UpdatesChangedListener> listeners = new LinkedList<UpdatesChangedListener>();

    public interface UpdatesChangedListener {
        public void updatesChanged();
        public void commentAdded();
    }

    public EditNoteActivity(Fragment fragment, View parent, long t) {
        super(fragment.getActivity());

        imageCache = ImageDiskCache.getInstance();
        this.fragment = fragment;
        this.activity = (AstridActivity) fragment.getActivity();

        linkColor = UpdateAdapter.getLinkColor(fragment);

        cameraButton = getDefaultCameraButton();

        DependencyInjectionService.getInstance().inject(this);
        setOrientation(VERTICAL);

        commentsBar = parent.findViewById(R.id.updatesFooter);
        parentView = parent;

        loadViewForTaskID(t);
    }

    private int getDefaultCameraButton() {
        return R.drawable.camera_button;
    }

    public void loadViewForTaskID(long t){
        try {
            task = PluginServices.getTaskService().fetchById(t, Task.NOTES, Task.ID, Task.UUID, Task.TITLE);
        } catch (SQLiteException e) {
            StartupService.handleSQLiteError(ContextManager.getContext(), e);
        }
        if(task == null) {
            return;
        }
        setUpInterface();
        setUpListAdapter();

        if(actFmPreferenceService.isLoggedIn()) {
            if(!task.containsNonNullValue(Task.UUID))
                refreshData(true, null);
            else {
                String fetchKey = LAST_FETCH_KEY + task.getId();
                long lastFetchDate = Preferences.getLong(fetchKey, 0);
                if(DateUtilities.now() > lastFetchDate + 300000L) {
                    refreshData(false, null);
                    Preferences.setLong(fetchKey, DateUtilities.now());
                } else {
                    loadingText.setText(R.string.ENA_no_comments);
                    if(items.size() == 0)
                        loadingText.setVisibility(View.VISIBLE);
                }
            }
        }
    }



    // --- UI preparation

    private void setUpInterface() {


        timerView = commentsBar.findViewById(R.id.timer_container);
        commentButton = commentsBar.findViewById(R.id.commentButton);
        commentField = (EditText) commentsBar.findViewById(R.id.commentField);
        commentField.setOnEditorActionListener(new OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if(actionId == EditorInfo.IME_NULL && commentField.getText().length() > 0) {
                    addComment();
                    return true;
                }
                return false;
            }
        });
        commentField.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                commentButton.setVisibility((s.length() > 0 || pendingCommentPicture != null) ? View.VISIBLE
                        : View.GONE);
                timerView.setVisibility((s.length() > 0 || pendingCommentPicture != null) ? View.GONE
                        : View.VISIBLE);
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                //
            }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //
            }
        });

        commentField.setOnFocusChangeListener(new OnFocusChangeListener() {

            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    timerView.setVisibility(View.GONE);
                    commentButton.setVisibility(View.VISIBLE);
                }
                else {
                    timerView.setVisibility(View.VISIBLE);
                    commentButton.setVisibility(View.GONE);
                }
            }
        });
        commentButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                addComment();
            }
        });

        final ClearImageCallback clearImage = new ClearImageCallback() {
            @Override
            public void clearImage() {
                pendingCommentPicture = null;
                pictureButton.setImageResource(cameraButton);
            }
        };
        pictureButton = (ImageButton) commentsBar.findViewById(R.id.picture);
        pictureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pendingCommentPicture != null)
                    ActFmCameraModule.showPictureLauncher(fragment, clearImage);
                else
                    ActFmCameraModule.showPictureLauncher(fragment, null);
                respondToPicture = true;
            }
        });
        if(!TextUtils.isEmpty(task.getValue(Task.NOTES))) {
            TextView notes = new TextView(getContext());
            notes.setLinkTextColor(Color.rgb(100, 160, 255));
            notes.setTextSize(18);
            notes.setText(task.getValue(Task.NOTES));
            notes.setPadding(5, 10, 5, 10);
            Linkify.addLinks(notes, Linkify.ALL);
        }

        Activity activity = fragment.getActivity();
        if (activity != null) {
            Bitmap bitmap = activity.getIntent().getParcelableExtra(TaskEditFragment.TOKEN_PICTURE_IN_PROGRESS);
            if (bitmap != null) {
                pendingCommentPicture = bitmap;
                pictureButton.setImageBitmap(pendingCommentPicture);
            }
        }

        //TODO add loading text back in
        //        loadingText = (TextView) findViewById(R.id.loading);
        loadingText = new TextView(getContext());
    }

    private void setUpListAdapter() {
        items.clear();
        this.removeAllViews();
        TodorooCursor<Metadata> notes = metadataService.query(
                Query.select(Metadata.PROPERTIES).where(
                        MetadataCriteria.byTaskAndwithKey(task.getId(),
                                NoteMetadata.METADATA_KEY)));
        try {
            Metadata metadata = new Metadata();
            for(notes.moveToFirst(); !notes.isAfterLast(); notes.moveToNext()) {
                metadata.readFromCursor(notes);
                items.add(NoteOrUpdate.fromMetadata(metadata));
            }
        } finally {
            notes.close();
        }


        TodorooCursor<UserActivity> updates = userActivityDao.query(Query.select(AndroidUtilities.addToArray(UserActivity.PROPERTIES, User.PROPERTIES))
                .where(UserActivity.TARGET_ID.eq(task.getUuid()))
                .join(Join.left(User.TABLE, UserActivity.USER_UUID.eq(User.UUID)))
                .orderBy(Order.desc(UserActivity.CREATED_AT)));
        try {
            UserActivity update = new UserActivity();
            User user = new User();
            for(updates.moveToFirst(); !updates.isAfterLast(); updates.moveToNext()) {
                update.clear();
                user.clear();

                update.readFromCursor(updates);
                user.readPropertiesFromCursor(updates);
                NoteOrUpdate noa = NoteOrUpdate.fromUpdate(activity, update, user, linkColor);
                if(noa != null)
                    items.add(noa);
            }
        } finally {
            updates.close();
        }

        Collections.sort(items, new Comparator<NoteOrUpdate>() {
            @Override
            public int compare(NoteOrUpdate a, NoteOrUpdate b) {
                if(a.createdAt < b.createdAt)
                    return 1;
                else if (a.createdAt == b.createdAt)
                    return 0;
                else
                    return -1;
            }
        });

        for (int i = 0; i < Math.min(items.size(), commentItems); i++) {
            View notesView = this.getUpdateNotes(items.get(i), this);
            this.addView(notesView);
        }

        if (items.size() > commentItems) {
            Button loadMore = new Button(getContext());
            loadMore.setText(R.string.TEA_load_more);
            loadMore.setBackgroundColor(Color.alpha(0));
            loadMore.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // Perform action on click
                    commentItems += 10;
                    setUpListAdapter();
                }
            });
            this.addView(loadMore);
        }
        else if (items.size() == 0) {
            TextView noUpdates = new TextView(getContext());
            noUpdates.setText(R.string.TEA_no_activity);
            noUpdates.setLayoutParams(new LayoutParams(LayoutParams.FILL_PARENT,
                    LayoutParams.WRAP_CONTENT));
            noUpdates.setPadding(10, 10, 10, 10);
            noUpdates.setGravity(Gravity.CENTER);
            noUpdates.setTextSize(16);
            this.addView(noUpdates);
        }


        for (UpdatesChangedListener l : listeners) {
            l.updatesChanged();
        }
    }



    public View getUpdateNotes(NoteOrUpdate note, ViewGroup parent) {
        View convertView = ((Activity)getContext()).getLayoutInflater().inflate(
                R.layout.update_adapter_row, parent, false);

        bindView(convertView, note);
        return convertView;
    }

    /** Helper method to set the contents and visibility of each field */
    public synchronized void bindView(View view, NoteOrUpdate item) {
        // picture
        final AsyncImageView pictureView = (AsyncImageView)view.findViewById(R.id.picture); {
            pictureView.setDefaultImageResource(R.drawable.icn_default_person_image);
            pictureView.setUrl(item.picture);

        }

        // name
        final TextView nameView = (TextView)view.findViewById(R.id.title); {
            nameView.setText(item.title);
            Linkify.addLinks(nameView, Linkify.ALL);
        }

        // date
        final TextView date = (TextView)view.findViewById(R.id.date); {
            CharSequence dateString = DateUtils.getRelativeTimeSpanString(item.createdAt,
                    DateUtilities.now(), DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);
            date.setText(dateString);
        }

        // picture
        final AsyncImageView commentPictureView = (AsyncImageView)view.findViewById(R.id.comment_picture); {
            UpdateAdapter.setupImagePopupForCommentView(view, commentPictureView, item.commentPicture, item.title.toString(), fragment, imageCache);
        }
    }

    public void refreshData(boolean manual, SyncResultCallback existingCallback) {
        if(!task.containsNonNullValue(Task.UUID)) {
            return;
        }

//        final SyncResultCallback callback;
//        if(existingCallback != null)
//            callback = existingCallback;
//        else {
//            callback = new ProgressBarSyncResultCallback(
//                    ((Activity)getContext()), (ProgressBar)parentView.findViewById(R.id.progressBar), new Runnable() {
//                        @Override
//                        public void run() {
//                            setUpListAdapter();
//                            loadingText.setText(R.string.ENA_no_comments);
//                            loadingText.setVisibility(items.size() == 0 ? View.VISIBLE : View.GONE);
//                        }
//                    });
//
//            callback.started();
//            callback.incrementMax(100);
//        }
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                Log.e("EditNoteActivity", "Refresh data callback");
            }
        };

        ActFmSyncThread.getInstance().enqueueMessage(new BriefMe<Task>(Task.class, task.getUuid(), task.getValue(Task.PUSHED_AT)), callback);


//        actFmSyncService.fetchUpdatesForTask(task, manual, new Runnable() {
//            @Override
//            public void run() {
//                callback.incrementProgress(50);
//                callback.finished();
//            }
//        });
//        callback.incrementProgress(50);
//        callback.finished();
//        callback.incrementProgress(50);
    }

    private void addComment() {
        addComment(commentField.getText().toString(), UserActivity.ACTION_TASK_COMMENT, true);
    }


    @SuppressWarnings("nls")
    private void addComment(String message, String actionCode, boolean usePicture) {
        // Allow for users to just add picture
        if (TextUtils.isEmpty(message) && usePicture) {
            message = " ";
        }
        UserActivity userActivity = new UserActivity();
        userActivity.setValue(UserActivity.MESSAGE, message);
        userActivity.setValue(UserActivity.ACTION, actionCode);
        userActivity.setValue(UserActivity.USER_UUID, Task.USER_ID_SELF);
        userActivity.setValue(UserActivity.TARGET_ID, task.getUuid());
        userActivity.setValue(UserActivity.TARGET_NAME, task.getValue(Task.TITLE));
        userActivity.setValue(UserActivity.CREATED_AT, DateUtilities.now());

        // TODO: Fix picture uploading
//        if (usePicture && pendingCommentPicture != null) {
//            update.setValue(Update.PICTURE, Update.PICTURE_LOADING);
//            try {
//                String updateString = ImageDiskCache.getPictureHash(update);
//                imageCache.put(updateString, pendingCommentPicture);
//                update.setValue(Update.PICTURE, updateString);
//            }
//            catch (Exception e) {
//                Log.e("EditNoteActivity", "Failed to put image to disk...");
//            }
//        }
//        update.putTransitory(SyncFlags.ACTFM_SUPPRESS_SYNC, true);
        userActivityDao.createNew(userActivity);
//
//        final long updateId = userActivity.getId();
//        final Bitmap tempPicture = usePicture ? pendingCommentPicture : null;
//        new Thread() {
//            @Override
//            public void run() {
//                actFmSyncService.pushUpdate(updateId, tempPicture);
//
//            }
//        }.start();
        commentField.setText(""); //$NON-NLS-1$

        pendingCommentPicture = usePicture ? null : pendingCommentPicture;
        if (usePicture) {
            Activity activity = fragment.getActivity();
            if (activity != null)
                activity.getIntent().removeExtra(TaskEditFragment.TOKEN_PICTURE_IN_PROGRESS);
        }
        pictureButton.setImageResource(cameraButton);
        StatisticsService.reportEvent(StatisticsConstants.ACTFM_TASK_COMMENT);

        setUpListAdapter();
        for (UpdatesChangedListener l : listeners) {
            l.commentAdded();
        }
    }

    public int numberOfComments() {
        return items.size();
    }

    private static class NoteOrUpdate {
        private final String picture;
        private final Spanned title;
        private final String commentPicture;
        private final long createdAt;

        public NoteOrUpdate(String picture, Spanned title, String commentPicture,
                long createdAt) {
            super();
            this.picture = picture;
            this.title = title;
            this.commentPicture = commentPicture;
            this.createdAt = createdAt;
        }

        public static NoteOrUpdate fromMetadata(Metadata m) {
            if(!m.containsNonNullValue(NoteMetadata.THUMBNAIL))
                m.setValue(NoteMetadata.THUMBNAIL, ""); //$NON-NLS-1$
            if(!m.containsNonNullValue(NoteMetadata.COMMENT_PICTURE))
                m.setValue(NoteMetadata.COMMENT_PICTURE, ""); //$NON-NLS-1$
            Spanned title = Html.fromHtml(String.format("%s\n%s", m.getValue(NoteMetadata.TITLE), m.getValue(NoteMetadata.BODY))); //$NON-NLS-1$
            return new NoteOrUpdate(m.getValue(NoteMetadata.THUMBNAIL),
                    title,
                    m.getValue(NoteMetadata.COMMENT_PICTURE),
                    m.getValue(Metadata.CREATION_DATE));
        }

        public static NoteOrUpdate fromUpdate(AstridActivity context, UserActivity u, User user, String linkColor) {
//            JSONObject user = ActFmPreferenceService.userFromModel(u);

            String commentPicture = u.getPictureUrl(UserActivity.PICTURE, RemoteModel.PICTURE_MEDIUM);

            Spanned title = UpdateAdapter.getUpdateComment(context, u, user, linkColor, UpdateAdapter.FROM_TASK_VIEW);
            return new NoteOrUpdate(user.getPictureUrl(User.PICTURE, RemoteModel.PICTURE_THUMB),
                    title,
                    commentPicture,
                    u.getValue(UserActivity.CREATED_AT));
        }

    }

    public void addListener(UpdatesChangedListener listener) {
        listeners.add(listener);
    }

    public void removeListener(UpdatesChangedListener listener) {
        if (listeners.contains(listener))
            listeners.remove(listener);
    }

    @Override
    public void timerStarted(Task t) {
        addComment(String.format("%s %s",  //$NON-NLS-1$
                getContext().getString(R.string.TEA_timer_comment_started),
                DateUtilities.getTimeString(getContext(), new Date())),
                UserActivity.ACTION_TASK_COMMENT,
                false);
    }

    @Override
    public void timerStopped(Task t) {
        String elapsedTime = DateUtils.formatElapsedTime(t.getValue(Task.ELAPSED_SECONDS));
        addComment(String.format("%s %s\n%s %s", //$NON-NLS-1$
                getContext().getString(R.string.TEA_timer_comment_stopped),
                DateUtilities.getTimeString(getContext(), new Date()),
                getContext().getString(R.string.TEA_timer_comment_spent),
                elapsedTime), UserActivity.ACTION_TASK_COMMENT, false);
    }

    /*
     * Call back from edit task when picture is added
     */
    public boolean activityResult(int requestCode, int resultCode, Intent data) {

        if (respondToPicture) {
            respondToPicture = false;
            CameraResultCallback callback = new CameraResultCallback() {
                @Override
                public void handleCameraResult(Bitmap bitmap) {
                    Activity activity = fragment.getActivity();
                    if (activity != null) {
                        activity.getIntent().putExtra(TaskEditFragment.TOKEN_PICTURE_IN_PROGRESS, bitmap);
                    }
                    pendingCommentPicture = bitmap;
                    pictureButton.setImageBitmap(pendingCommentPicture);
                    commentField.requestFocus();
                }
            };

            return (ActFmCameraModule.activityResult((Activity)getContext(),
                    requestCode, resultCode, data, callback));
        } else {
            return false;
        }
    }

}
