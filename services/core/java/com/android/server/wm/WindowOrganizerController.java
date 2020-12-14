/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import static android.Manifest.permission.READ_FRAME_BUFFER;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.ActivityTaskManagerService.LAYOUT_REASON_CONFIG_CHANGED;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.Task.FLAG_FORCE_HIDDEN_FOR_TASK_ORG;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.IDisplayAreaOrganizerController;
import android.window.ITaskOrganizerController;
import android.window.ITransitionPlayer;
import android.window.IWindowContainerTransactionCallback;
import android.window.IWindowOrganizerController;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Server side implementation for the interface for organizing windows
 * @see android.window.WindowOrganizer
 */
class WindowOrganizerController extends IWindowOrganizerController.Stub
    implements BLASTSyncEngine.TransactionReadyListener {

    private static final String TAG = "WindowOrganizerController";

    /** Flag indicating that an applied transaction may have effected lifecycle */
    private static final int TRANSACT_EFFECTS_CLIENT_CONFIG = 1;
    private static final int TRANSACT_EFFECTS_LIFECYCLE = 1 << 1;

    /**
     * Masks specifying which configurations task-organizers can control. Incoming transactions
     * will be filtered to only include these.
     */
    static final int CONTROLLABLE_CONFIGS = ActivityInfo.CONFIG_WINDOW_CONFIGURATION
            | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE | ActivityInfo.CONFIG_SCREEN_SIZE;
    static final int CONTROLLABLE_WINDOW_CONFIGS = WindowConfiguration.WINDOW_CONFIG_BOUNDS
            | WindowConfiguration.WINDOW_CONFIG_APP_BOUNDS;

    private final ActivityTaskManagerService mService;
    private final WindowManagerGlobalLock mGlobalLock;

    private final HashMap<Integer, IWindowContainerTransactionCallback>
            mTransactionCallbacksByPendingSyncId = new HashMap();

    final TaskOrganizerController mTaskOrganizerController;
    final DisplayAreaOrganizerController mDisplayAreaOrganizerController;

    final TransitionController mTransitionController;

    WindowOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
        mTaskOrganizerController = new TaskOrganizerController(mService);
        mDisplayAreaOrganizerController = new DisplayAreaOrganizerController(mService);
        mTransitionController = new TransitionController(atm);
    }

    TransitionController getTransitionController() {
        return mTransitionController;
    }

    @Override
    public void applyTransaction(WindowContainerTransaction t) {
        enforceTaskPermission("applyTransaction()");
        if (t == null) {
            throw new IllegalArgumentException("Null transaction passed to applySyncTransaction");
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                applyTransaction(t, -1 /*syncId*/, null /*transition*/);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int applySyncTransaction(WindowContainerTransaction t,
            IWindowContainerTransactionCallback callback) {
        enforceTaskPermission("applySyncTransaction()");
        if (t == null) {
            throw new IllegalArgumentException("Null transaction passed to applySyncTransaction");
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                /**
                 * If callback is non-null we are looking to synchronize this transaction by
                 * collecting all the results in to a SurfaceFlinger transaction and then delivering
                 * that to the given transaction ready callback. See {@link BLASTSyncEngine} for the
                 * details of the operation. But at a high level we create a sync operation with a
                 * given ID and an associated callback. Then we notify each WindowContainer in this
                 * WindowContainer transaction that it is participating in a sync operation with
                 * that ID. Once everything is notified we tell the BLASTSyncEngine "setSyncReady"
                 * which means that we have added everything to the set. At any point after this,
                 * all the WindowContainers will eventually finish applying their changes and notify
                 * the BLASTSyncEngine which will deliver the Transaction to the callback.
                 */
                int syncId = -1;
                if (callback != null) {
                    syncId = startSyncWithOrganizer(callback);
                }
                applyTransaction(t, syncId, null /*transition*/);
                if (syncId >= 0) {
                    setSyncReady(syncId);
                }
                return syncId;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public IBinder startTransition(int type, @Nullable IBinder transitionToken,
            @Nullable WindowContainerTransaction t) {
        enforceTaskPermission("startTransition()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                Transition transition = Transition.fromBinder(transitionToken);
                // In cases where transition is already provided, the "readiness lifecycle" of the
                // transition is determined outside of this transaction. However, if this is a
                // direct call from shell, the entire transition lifecycle is contained in the
                // provided transaction and thus we can setReady immediately after apply.
                boolean needsSetReady = transition == null && t != null;
                if (transition == null) {
                    if (type < 0) {
                        throw new IllegalArgumentException("Can't create transition with no type");
                    }
                    transition = mTransitionController.createTransition(type);
                }
                transition.start();
                if (t == null) {
                    t = new WindowContainerTransaction();
                }
                applyTransaction(t, -1 /*syncId*/, transition);
                if (needsSetReady) {
                    transition.setReady();
                }
                return transition;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int finishTransition(@NonNull IBinder transitionToken,
            @Nullable WindowContainerTransaction t,
            @Nullable IWindowContainerTransactionCallback callback) {
        enforceTaskPermission("finishTransition()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                int syncId = -1;
                if (t != null && callback != null) {
                    syncId = startSyncWithOrganizer(callback);
                }
                getTransitionController().finishTransition(transitionToken);
                if (t != null) {
                    applyTransaction(t, syncId, null /*transition*/);
                }
                if (syncId >= 0) {
                    setSyncReady(syncId);
                }
                return syncId;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * @param syncId If non-null, this will be a sync-transaction.
     * @param transition A transition to collect changes into.
     */
    private void applyTransaction(@NonNull WindowContainerTransaction t, int syncId,
            @Nullable Transition transition) {
        int effects = 0;
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Apply window transaction, syncId=%d", syncId);
        mService.deferWindowLayout();
        try {
            ArraySet<WindowContainer> haveConfigChanges = new ArraySet<>();
            Iterator<Map.Entry<IBinder, WindowContainerTransaction.Change>> entries =
                    t.getChanges().entrySet().iterator();
            while (entries.hasNext()) {
                final Map.Entry<IBinder, WindowContainerTransaction.Change> entry = entries.next();
                final WindowContainer wc = WindowContainer.fromBinder(entry.getKey());
                if (wc == null || !wc.isAttached()) {
                    Slog.e(TAG, "Attempt to operate on detached container: " + wc);
                    continue;
                }
                // Make sure we add to the syncSet before performing
                // operations so we don't end up splitting effects between the WM
                // pending transaction and the BLASTSync transaction.
                if (syncId >= 0) {
                    addToSyncSet(syncId, wc);
                }
                if (transition != null) transition.collect(wc);

                int containerEffect = applyWindowContainerChange(wc, entry.getValue());
                effects |= containerEffect;

                // Lifecycle changes will trigger ensureConfig for everything.
                if ((effects & TRANSACT_EFFECTS_LIFECYCLE) == 0
                        && (containerEffect & TRANSACT_EFFECTS_CLIENT_CONFIG) != 0) {
                    haveConfigChanges.add(wc);
                }
            }
            // Hierarchy changes
            final List<WindowContainerTransaction.HierarchyOp> hops = t.getHierarchyOps();
            for (int i = 0, n = hops.size(); i < n; ++i) {
                final WindowContainerTransaction.HierarchyOp hop = hops.get(i);
                switch (hop.getType()) {
                    case HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT:
                        final Task task = WindowContainer.fromBinder(hop.getContainer()).asTask();
                            task.getDisplayArea().setLaunchRootTask(task,
                                    hop.getWindowingModes(), hop.getActivityTypes());
                        break;
                    case HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT:
                        effects |= reparentChildrenTasksHierarchyOp(hop, transition, syncId);
                        break;
                    case HIERARCHY_OP_TYPE_REORDER:
                    case HIERARCHY_OP_TYPE_REPARENT:
                        final WindowContainer wc = WindowContainer.fromBinder(hop.getContainer());
                        if (wc == null || !wc.isAttached()) {
                            Slog.e(TAG, "Attempt to operate on detached container: " + wc);
                            continue;
                        }
                        if (syncId >= 0) {
                            addToSyncSet(syncId, wc);
                        }
                        if (transition != null) {
                            transition.collect(wc);
                            if (hop.isReparent()) {
                                if (wc.getParent() != null) {
                                    // Collect the current parent. It's visibility may change as
                                    // a result of this reparenting.
                                    transition.collect(wc.getParent());
                                }
                                if (hop.getNewParent() != null) {
                                    final WindowContainer parentWc =
                                            WindowContainer.fromBinder(hop.getNewParent());
                                    if (parentWc == null) {
                                        Slog.e(TAG, "Can't resolve parent window from token");
                                        continue;
                                    }
                                    transition.collect(parentWc);
                                }
                            }
                        }
                        effects |= sanitizeAndApplyHierarchyOp(wc, hop);
                }
            }
            // Queue-up bounds-change transactions for tasks which are now organized. Do
            // this after hierarchy ops so we have the final organized state.
            entries = t.getChanges().entrySet().iterator();
            while (entries.hasNext()) {
                final Map.Entry<IBinder, WindowContainerTransaction.Change> entry = entries.next();
                final WindowContainer wc = WindowContainer.fromBinder(entry.getKey());
                if (wc == null || !wc.isAttached()) {
                    Slog.e(TAG, "Attempt to operate on detached container: " + wc);
                    continue;
                }
                final Task task = wc.asTask();
                final Rect surfaceBounds = entry.getValue().getBoundsChangeSurfaceBounds();
                if (task == null || !task.isAttached() || surfaceBounds == null) {
                    continue;
                }
                if (!task.isOrganized()) {
                    final Task parent = task.getParent() != null ? task.getParent().asTask() : null;
                    // Also allow direct children of created-by-organizer tasks to be
                    // controlled. In the future, these will become organized anyways.
                    if (parent == null || !parent.mCreatedByOrganizer) {
                        throw new IllegalArgumentException(
                                "Can't manipulate non-organized task surface " + task);
                    }
                }
                final SurfaceControl.Transaction sft = new SurfaceControl.Transaction();
                final SurfaceControl sc = task.getSurfaceControl();
                sft.setPosition(sc, surfaceBounds.left, surfaceBounds.top);
                if (surfaceBounds.isEmpty()) {
                    sft.setWindowCrop(sc, null);
                } else {
                    sft.setWindowCrop(sc, surfaceBounds.width(), surfaceBounds.height());
                }
                task.setMainWindowSizeChangeTransaction(sft);
            }
            if ((effects & TRANSACT_EFFECTS_LIFECYCLE) != 0) {
                // Already calls ensureActivityConfig
                mService.mRootWindowContainer.ensureActivitiesVisible(null, 0, PRESERVE_WINDOWS);
                mService.mRootWindowContainer.resumeFocusedTasksTopActivities();
            } else if ((effects & TRANSACT_EFFECTS_CLIENT_CONFIG) != 0) {
                final PooledConsumer f = PooledLambda.obtainConsumer(
                        ActivityRecord::ensureActivityConfiguration,
                        PooledLambda.__(ActivityRecord.class), 0,
                        true /* preserveWindow */);
                try {
                    for (int i = haveConfigChanges.size() - 1; i >= 0; --i) {
                        haveConfigChanges.valueAt(i).forAllActivities(f);
                    }
                } finally {
                    f.recycle();
                }
            }

            if ((effects & TRANSACT_EFFECTS_CLIENT_CONFIG) == 0) {
                mService.addWindowLayoutReasons(LAYOUT_REASON_CONFIG_CHANGED);
            }
        } finally {
            mService.continueWindowLayout();
        }
    }

    private int applyChanges(WindowContainer container, WindowContainerTransaction.Change change) {
        // The "client"-facing API should prevent bad changes; however, just in case, sanitize
        // masks here.
        final int configMask = change.getConfigSetMask() & CONTROLLABLE_CONFIGS;
        final int windowMask = change.getWindowSetMask() & CONTROLLABLE_WINDOW_CONFIGS;
        int effects = 0;
        final int windowingMode = change.getWindowingMode();
        if (configMask != 0) {
            if (windowingMode > -1 && windowingMode != container.getWindowingMode()) {
                // Special handling for when we are setting a windowingMode in the same transaction.
                // Setting the windowingMode is going to call onConfigurationChanged so we don't
                // need it called right now. Additionally, some logic requires everything in the
                // configuration to change at the same time (ie. surface-freezer requires bounds
                // and mode to change at the same time).
                final Configuration c = container.getRequestedOverrideConfiguration();
                c.setTo(change.getConfiguration(), configMask, windowMask);
            } else {
                final Configuration c =
                        new Configuration(container.getRequestedOverrideConfiguration());
                c.setTo(change.getConfiguration(), configMask, windowMask);
                container.onRequestedOverrideConfigurationChanged(c);
                // TODO(b/145675353): remove the following once we could apply new bounds to the
                // pinned stack together with its children.
            }
            resizePinnedStackIfNeeded(container, configMask, windowMask,
                    container.getRequestedOverrideConfiguration());
            effects |= TRANSACT_EFFECTS_CLIENT_CONFIG;
        }
        if ((change.getChangeMask() & WindowContainerTransaction.Change.CHANGE_FOCUSABLE) != 0) {
            if (container.setFocusable(change.getFocusable())) {
                effects |= TRANSACT_EFFECTS_LIFECYCLE;
            }
        }

        if (windowingMode > -1) {
            container.setWindowingMode(windowingMode);
        }
        return effects;
    }

    private int applyTaskChanges(Task tr, WindowContainerTransaction.Change c) {
        int effects = 0;
        final SurfaceControl.Transaction t = c.getBoundsChangeTransaction();

        if ((c.getChangeMask() & WindowContainerTransaction.Change.CHANGE_HIDDEN) != 0) {
            if (tr.setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, c.getHidden())) {
                effects = TRANSACT_EFFECTS_LIFECYCLE;
            }
        }

        final int childWindowingMode = c.getActivityWindowingMode();
        if (childWindowingMode > -1) {
            tr.setActivityWindowingMode(childWindowingMode);
        }

        if (t != null) {
            tr.setMainWindowSizeChangeTransaction(t);
        }

        Rect enterPipBounds = c.getEnterPipBounds();
        if (enterPipBounds != null) {
            mService.mTaskSupervisor.updatePictureInPictureMode(tr, enterPipBounds, true);
        }

        return effects;
    }

    private int applyDisplayAreaChanges(DisplayArea displayArea,
            WindowContainerTransaction.Change c) {
        final int[] effects = new int[1];

        if ((c.getChangeMask()
                & WindowContainerTransaction.Change.CHANGE_IGNORE_ORIENTATION_REQUEST) != 0) {
            if (displayArea.setIgnoreOrientationRequest(c.getIgnoreOrientationRequest())) {
                effects[0] |= TRANSACT_EFFECTS_LIFECYCLE;
            }
        }

        displayArea.forAllTasks(task -> {
            Task tr = (Task) task;
            if ((c.getChangeMask() & WindowContainerTransaction.Change.CHANGE_HIDDEN) != 0) {
                if (tr.setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, c.getHidden())) {
                    effects[0] |= TRANSACT_EFFECTS_LIFECYCLE;
                }
            }
        });

        return effects[0];
    }

    private int sanitizeAndApplyHierarchyOp(WindowContainer container,
            WindowContainerTransaction.HierarchyOp hop) {
        final Task task = container.asTask();
        if (task == null) {
            throw new IllegalArgumentException("Invalid container in hierarchy op");
        }
        final DisplayContent dc = task.getDisplayContent();
        if (dc == null) {
            Slog.w(TAG, "Container is no longer attached: " + task);
            return 0;
        }
        final Task as = task;

        if (hop.isReparent()) {
            final boolean isNonOrganizedRootableTask =
                    task.isRootTask() || task.getParent().asTask().mCreatedByOrganizer;
            if (isNonOrganizedRootableTask) {
                WindowContainer newParent = hop.getNewParent() == null
                        ? dc.getDefaultTaskDisplayArea()
                        : WindowContainer.fromBinder(hop.getNewParent());
                if (newParent == null) {
                    Slog.e(TAG, "Can't resolve parent window from token");
                    return 0;
                }
                if (task.getParent() != newParent) {
                    if (newParent instanceof TaskDisplayArea) {
                        // For now, reparenting to displayarea is different from other reparents...
                        as.reparent((TaskDisplayArea) newParent, hop.getToTop());
                    } else if (newParent.inMultiWindowMode() && !task.isResizeable()
                            && task.isLeafTask()) {
                        Slog.w(TAG, "Can't support task that doesn't support multi-window mode in"
                                + " multi-window mode... newParent=" + newParent + " task=" + task);
                        return 0;
                    } else {
                        task.reparent((Task) newParent,
                                hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM,
                                false /*moveParents*/, "sanitizeAndApplyHierarchyOp");
                    }
                } else {
                    final Task rootTask = (Task) (
                            (newParent != null && !(newParent instanceof TaskDisplayArea))
                                    ? newParent : task.getRootTask());
                    as.getDisplayArea().positionChildAt(
                            hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM, rootTask,
                            false /* includingParents */);
                }
            } else {
                throw new RuntimeException("Reparenting leaf Tasks is not supported now. " + task);
            }
        } else {
            task.getParent().positionChildAt(
                    hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM,
                    task, false /* includingParents */);
        }
        return TRANSACT_EFFECTS_LIFECYCLE;
    }

    private int reparentChildrenTasksHierarchyOp(WindowContainerTransaction.HierarchyOp hop,
            @Nullable Transition transition, int syncId) {
        WindowContainer currentParent = hop.getContainer() != null
                ? WindowContainer.fromBinder(hop.getContainer()) : null;
        WindowContainer newParent = hop.getNewParent() != null
                ? WindowContainer.fromBinder(hop.getNewParent()) : null;
        if (currentParent == null && newParent == null) {
            throw new IllegalArgumentException("reparentChildrenTasksHierarchyOp: " + hop);
        } else if (currentParent == null) {
            currentParent = newParent.asTask().getDisplayContent().getDefaultTaskDisplayArea();
        } else if (newParent == null) {
            newParent = currentParent.asTask().getDisplayContent().getDefaultTaskDisplayArea();
        }

        if (currentParent == newParent) {
            Slog.e(TAG, "reparentChildrenTasksHierarchyOp parent not changing: " + hop);
            return 0;
        }
        if (!currentParent.isAttached()) {
            Slog.e(TAG, "reparentChildrenTasksHierarchyOp currentParent detached="
                    + currentParent + " hop=" + hop);
            return 0;
        }
        if (!newParent.isAttached()) {
            Slog.e(TAG, "reparentChildrenTasksHierarchyOp newParent detached="
                    + newParent + " hop=" + hop);
            return 0;
        }

        final boolean newParentInMultiWindow = newParent.inMultiWindowMode();
        final WindowContainer finalCurrentParent = currentParent;
        Slog.i(TAG, "reparentChildrenTasksHierarchyOp"
                + " currentParent=" + currentParent + " newParent=" + newParent + " hop=" + hop);

        // We want to collect the tasks first before re-parenting to avoid array shifting on us.
        final ArrayList<Task> tasksToReparent = new ArrayList<>();

        currentParent.forAllTasks((Consumer<Task>) (task) -> {
            Slog.i(TAG, " Processing task=" + task);
            if (task.mCreatedByOrganizer
                    || task.getParent() != finalCurrentParent) {
                // We only care about non-organized task that are direct children of the thing we
                // are reparenting from.
                return;
            }

            if (newParentInMultiWindow && !task.isResizeable()) {
                Slog.e(TAG, "reparentChildrenTasksHierarchyOp non-resizeable task=" + task);
            }

            if (!ArrayUtils.contains(hop.getActivityTypes(), task.getActivityType())) return;
            if (!ArrayUtils.contains(hop.getWindowingModes(), task.getWindowingMode())) return;

            tasksToReparent.add(task);
        }, !hop.getToTop());

        final int count = tasksToReparent.size();
        for (int i = 0; i < count; ++i) {
            final Task task = tasksToReparent.get(i);
            if (syncId >= 0) {
                addToSyncSet(syncId, task);
            }
            if (transition != null) transition.collect(task);

            if (newParent instanceof TaskDisplayArea) {
                // For now, reparenting to display area is different from other reparents...
                task.reparent((TaskDisplayArea) newParent, hop.getToTop());
            } else {
                task.reparent((Task) newParent,
                        hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM,
                        false /*moveParents*/, "processChildrenTaskReparentHierarchyOp");
            }
        }

        if (transition != null) transition.collect(newParent);

        return TRANSACT_EFFECTS_LIFECYCLE;
    }

    private void sanitizeWindowContainer(WindowContainer wc) {
        if (!(wc instanceof Task) && !(wc instanceof DisplayArea)) {
            throw new RuntimeException("Invalid token in task or displayArea transaction");
        }
    }

    private int applyWindowContainerChange(WindowContainer wc,
            WindowContainerTransaction.Change c) {
        sanitizeWindowContainer(wc);

        int effects = applyChanges(wc, c);

        if (wc instanceof DisplayArea) {
            effects |= applyDisplayAreaChanges(wc.asDisplayArea(), c);
        } else if (wc instanceof Task) {
            effects |= applyTaskChanges(wc.asTask(), c);
        }

        return effects;
    }

    private void resizePinnedStackIfNeeded(ConfigurationContainer container, int configMask,
            int windowMask, Configuration config) {
        if ((container instanceof Task)
                && ((configMask & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0)
                && ((windowMask & WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0)) {
            final Task stack = (Task) container;
            if (stack.inPinnedWindowingMode()) {
                stack.resize(config.windowConfiguration.getBounds(),
                        PRESERVE_WINDOWS, true /* deferResume */);
            }
        }
    }

    @Override
    public ITaskOrganizerController getTaskOrganizerController() {
        enforceTaskPermission("getTaskOrganizerController()");
        return mTaskOrganizerController;
    }

    @Override
    public IDisplayAreaOrganizerController getDisplayAreaOrganizerController() {
        enforceTaskPermission("getDisplayAreaOrganizerController()");
        return mDisplayAreaOrganizerController;
    }

    @VisibleForTesting
    int startSyncWithOrganizer(IWindowContainerTransactionCallback callback) {
        int id = mService.mWindowManager.mSyncEngine.startSyncSet(this);
        mTransactionCallbacksByPendingSyncId.put(id, callback);
        return id;
    }

    @VisibleForTesting
    void setSyncReady(int id) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Set sync ready, syncId=%d", id);
        mService.mWindowManager.mSyncEngine.setReady(id);
    }

    @VisibleForTesting
    void addToSyncSet(int syncId, WindowContainer wc) {
        mService.mWindowManager.mSyncEngine.addToSyncSet(syncId, wc);
    }

    @Override
    public void onTransactionReady(int syncId, SurfaceControl.Transaction t) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Transaction ready, syncId=%d", syncId);
        final IWindowContainerTransactionCallback callback =
                mTransactionCallbacksByPendingSyncId.get(syncId);

        try {
            callback.onTransactionReady(syncId, t);
        } catch (RemoteException e) {
            // If there's an exception when trying to send the mergedTransaction to the client, we
            // should immediately apply it here so the transactions aren't lost.
            t.apply();
        }

        mTransactionCallbacksByPendingSyncId.remove(syncId);
    }

    @Override
    public boolean takeScreenshot(WindowContainerToken token, SurfaceControl outSurfaceControl) {
        mService.mAmInternal.enforceCallingPermission(READ_FRAME_BUFFER, "takeScreenshot()");
        final WindowContainer wc = WindowContainer.fromBinder(token.asBinder());
        if (wc == null) {
            throw new RuntimeException("Invalid token in screenshot transaction");
        }

        final Rect bounds = new Rect();
        wc.getBounds(bounds);
        bounds.offsetTo(0, 0);
        SurfaceControl.ScreenshotHardwareBuffer buffer = SurfaceControl.captureLayers(
                wc.getSurfaceControl(), bounds, 1);

        if (buffer == null || buffer.getHardwareBuffer() == null) {
            return false;
        }

        SurfaceControl screenshot = mService.mWindowManager.mSurfaceControlFactory.apply(null)
                .setName(wc.getName() + " - Organizer Screenshot")
                .setBufferSize(bounds.width(), bounds.height())
                .setFormat(PixelFormat.TRANSLUCENT)
                .setParent(wc.getParentSurfaceControl())
                .setCallsite("WindowOrganizerController.takeScreenshot")
                .build();

        Surface surface = new Surface();
        surface.copyFrom(screenshot);
        surface.attachAndQueueBufferWithColorSpace(buffer.getHardwareBuffer(), null);
        surface.release();

        outSurfaceControl.copyFrom(screenshot, "WindowOrganizerController.takeScreenshot");
        return true;
    }

    @Override
    public void registerTransitionPlayer(ITransitionPlayer player) {
        enforceTaskPermission("registerTransitionPlayer()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mTransitionController.registerTransitionPlayer(player);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void enforceTaskPermission(String func) {
        mService.enforceTaskPermission(func);
    }
}
