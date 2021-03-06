package com.todoroo.astrid.gtasks;

import android.os.Bundle;

import com.commonsware.cwac.tlv.TouchListView;
import com.commonsware.cwac.tlv.TouchListView.DropListener;
import com.commonsware.cwac.tlv.TouchListView.SwipeListener;
import com.timsu.astrid.R;
import com.todoroo.andlib.data.Property.IntegerProperty;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.utility.DialogUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.activity.DraggableTaskListActivity;
import com.todoroo.astrid.gtasks.sync.GtasksSyncOnSaveService;

public class GtasksListActivity extends DraggableTaskListActivity {

    @Autowired private GtasksTaskListUpdater gtasksTaskListUpdater;

    @Autowired private GtasksSyncOnSaveService gtasksSyncOnSaveService;

    @Autowired private GtasksMetadataService gtasksMetadataService;

    @Override
    protected IntegerProperty getIndentProperty() {
        return GtasksMetadata.INDENT;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        getTouchListView().setDropListener(dropListener);
        getTouchListView().setSwipeListener(swipeListener);

        if(!Preferences.getBoolean(GtasksPreferenceService.PREF_SHOWN_LIST_HELP, false)) {
            Preferences.setBoolean(GtasksPreferenceService.PREF_SHOWN_LIST_HELP, true);
            DialogUtilities.okDialog(this,
                    getString(R.string.gtasks_help_title),
                    android.R.drawable.ic_dialog_info,
                    getString(R.string.gtasks_help_body), null);
        }
    }

    private final TouchListView.DropListener dropListener = new DropListener() {
        @Override
        public void drop(int from, int to) {
            long targetTaskId = taskAdapter.getItemId(from);
            long destinationTaskId = taskAdapter.getItemId(to);

            if(to == getListView().getCount() - 1)
                gtasksTaskListUpdater.moveTo(targetTaskId, -1);
            else
                gtasksTaskListUpdater.moveTo(targetTaskId, destinationTaskId);
            gtasksSyncOnSaveService.triggerMoveForMetadata(gtasksMetadataService.getTaskMetadata(targetTaskId));
            loadTaskListContent(true);
        }
    };

    private final TouchListView.SwipeListener swipeListener = new SwipeListener() {
        @Override
        public void swipeRight(int which) {
            long targetTaskId = taskAdapter.getItemId(which);
            gtasksTaskListUpdater.indent(targetTaskId, 1);
            gtasksSyncOnSaveService.triggerMoveForMetadata(gtasksMetadataService.getTaskMetadata(targetTaskId));
            loadTaskListContent(true);
        }

        @Override
        public void swipeLeft(int which) {
            long targetTaskId = taskAdapter.getItemId(which);
            gtasksTaskListUpdater.indent(targetTaskId, -1);
            gtasksSyncOnSaveService.triggerMoveForMetadata(gtasksMetadataService.getTaskMetadata(targetTaskId));
            loadTaskListContent(true);
        }
    };

}
