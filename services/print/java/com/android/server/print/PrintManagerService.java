/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.print;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.print.IPrintDocumentAdapter;
import android.print.IPrintJobStateChangeListener;
import android.print.IPrintManager;
import android.print.IPrinterDiscoveryObserver;
import android.print.PrintAttributes;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import android.printservice.PrintServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.List;

/**
 * SystemService wrapper for the PrintManager implementation. Publishes
 * Context.PRINT_SERVICE.
 * PrintManager implementation is contained within.
 */

public final class PrintManagerService extends SystemService {
    private final PrintManagerImpl mPrintManagerImpl;

    public PrintManagerService(Context context) {
        super(context);
        mPrintManagerImpl = new PrintManagerImpl(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.PRINT_SERVICE, mPrintManagerImpl);
    }

    @Override
    public void onUnlockUser(int userHandle) {
        mPrintManagerImpl.handleUserUnlocked(userHandle);
    }

    @Override
    public void onStopUser(int userHandle) {
        mPrintManagerImpl.handleUserStopped(userHandle);
    }

    class PrintManagerImpl extends IPrintManager.Stub {
        private static final int BACKGROUND_USER_ID = -10;

        private final Object mLock = new Object();

        private final Context mContext;

        private final UserManager mUserManager;

        private final SparseArray<UserState> mUserStates = new SparseArray<>();

        PrintManagerImpl(Context context) {
            mContext = context;
            mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            registerContentObservers();
            registerBroadcastReceivers();
        }

        @Override
        public Bundle print(String printJobName, IPrintDocumentAdapter adapter,
                PrintAttributes attributes, String packageName, int appId, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final int resolvedAppId;
            final UserState userState;
            final String resolvedPackageName;
            synchronized (mLock) {
                // Only the current group members can start new print jobs.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return null;
                }
                resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
                resolvedPackageName = resolveCallingPackageNameEnforcingSecurity(packageName);
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return userState.print(printJobName, adapter, attributes,
                        resolvedPackageName, resolvedAppId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<PrintJobInfo> getPrintJobInfos(int appId, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final int resolvedAppId;
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can query for state of print jobs.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return null;
                }
                resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return userState.getPrintJobInfos(resolvedAppId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public PrintJobInfo getPrintJobInfo(PrintJobId printJobId, int appId, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final int resolvedAppId;
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can query for state of a print job.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return null;
                }
                resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return userState.getPrintJobInfo(printJobId, resolvedAppId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Icon getCustomPrinterIcon(PrinterId printerId, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can get the printer icons.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return null;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return userState.getCustomPrinterIcon(printerId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void cancelPrintJob(PrintJobId printJobId, int appId, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final int resolvedAppId;
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can cancel a print job.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.cancelPrintJob(printJobId, resolvedAppId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void restartPrintJob(PrintJobId printJobId, int appId, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final int resolvedAppId;
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can restart a print job.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.restartPrintJob(printJobId, resolvedAppId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<PrintServiceInfo> getEnabledPrintServices(int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can get enabled services.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return null;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);

                // The user state might be updated via the same observer-set as the caller of this
                // interface. If the caller is called back first the user state is not yet updated
                // and the user gets and inconsistent view. Hence force an update.
                userState.updateIfNeededLocked();
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return userState.getEnabledPrintServices();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<PrintServiceInfo> getInstalledPrintServices(int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can get installed services.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return null;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return userState.getInstalledPrintServices();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void createPrinterDiscoverySession(IPrinterDiscoveryObserver observer,
                int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can create a discovery session.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.createPrinterDiscoverySession(observer);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void destroyPrinterDiscoverySession(IPrinterDiscoveryObserver observer,
                int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can destroy a discovery session.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.destroyPrinterDiscoverySession(observer);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void startPrinterDiscovery(IPrinterDiscoveryObserver observer,
                List<PrinterId> priorityList, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can start discovery.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.startPrinterDiscovery(observer, priorityList);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void stopPrinterDiscovery(IPrinterDiscoveryObserver observer, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can stop discovery.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.stopPrinterDiscovery(observer);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void validatePrinters(List<PrinterId> printerIds, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can validate printers.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.validatePrinters(printerIds);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void startPrinterStateTracking(PrinterId printerId, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can start printer tracking.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.startPrinterStateTracking(printerId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void stopPrinterStateTracking(PrinterId printerId, int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can stop printer tracking.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.stopPrinterStateTracking(printerId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void addPrintJobStateChangeListener(IPrintJobStateChangeListener listener,
                int appId, int userId) throws RemoteException {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final int resolvedAppId;
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can add a print job listener.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.addPrintJobStateChangeListener(listener, resolvedAppId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void removePrintJobStateChangeListener(IPrintJobStateChangeListener listener,
                int userId) {
            final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
            final UserState userState;
            synchronized (mLock) {
                // Only the current group members can remove a print job listener.
                if (resolveCallingProfileParentLocked(resolvedUserId) != getCurrentUserId()) {
                    return;
                }
                userState = getOrCreateUserStateLocked(resolvedUserId);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                userState.removePrintJobStateChangeListener(listener);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump PrintManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            synchronized (mLock) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    pw.println("PRINT MANAGER STATE (dumpsys print)");
                    final int userStateCount = mUserStates.size();
                    for (int i = 0; i < userStateCount; i++) {
                        UserState userState = mUserStates.valueAt(i);
                        userState.dump(fd, pw, "");
                        pw.println();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        private void registerContentObservers() {
            final Uri enabledPrintServicesUri = Settings.Secure.getUriFor(
                    Settings.Secure.DISABLED_PRINT_SERVICES);
            ContentObserver observer = new ContentObserver(BackgroundThread.getHandler()) {
                @Override
                public void onChange(boolean selfChange, Uri uri, int userId) {
                    if (enabledPrintServicesUri.equals(uri)) {
                        synchronized (mLock) {
                            final int userCount = mUserStates.size();
                            for (int i = 0; i < userCount; i++) {
                                if (userId == UserHandle.USER_ALL
                                        || userId == mUserStates.keyAt(i)) {
                                    mUserStates.valueAt(i).updateIfNeededLocked();
                                }
                            }
                        }
                    }
                }
            };

            mContext.getContentResolver().registerContentObserver(enabledPrintServicesUri,
                    false, observer, UserHandle.USER_ALL);
        }

        private void registerBroadcastReceivers() {
            PackageMonitor monitor = new PackageMonitor() {
                private void updateServices(String packageName) {
                    if (!mUserManager.isUserUnlocked(getChangingUserId())) return;
                    synchronized (mLock) {
                        // A background user/profile's print jobs are running but there is
                        // no UI shown. Hence, if the packages of such a user change we need
                        // to handle it as the change may affect ongoing print jobs.
                        boolean servicesChanged = false;
                        UserState userState = getOrCreateUserStateLocked(getChangingUserId());

                        List<PrintServiceInfo> installedServices = userState
                                .getInstalledPrintServices();
                        final int numInstalledServices = installedServices.size();
                        for (int i = 0; i < numInstalledServices; i++) {
                            if (installedServices.get(i).getResolveInfo().serviceInfo.packageName
                                    .equals(packageName)) {
                                servicesChanged = true;
                                break;
                            }
                        }
                        if (servicesChanged) {
                            userState.updateIfNeededLocked();
                        }
                    }
                }

                @Override
                public void onPackageModified(String packageName) {
                    if (!mUserManager.isUserUnlocked(getChangingUserId())) return;
                    updateServices(packageName);
                    getOrCreateUserStateLocked(getChangingUserId()).prunePrintServices();
                }

                @Override
                public void onPackageRemoved(String packageName, int uid) {
                    if (!mUserManager.isUserUnlocked(getChangingUserId())) return;
                    updateServices(packageName);
                    getOrCreateUserStateLocked(getChangingUserId()).prunePrintServices();
                }

                @Override
                public boolean onHandleForceStop(Intent intent, String[] stoppedPackages,
                        int uid, boolean doit) {
                    if (!mUserManager.isUserUnlocked(getChangingUserId())) return false;
                    synchronized (mLock) {
                        // A background user/profile's print jobs are running but there is
                        // no UI shown. Hence, if the packages of such a user change we need
                        // to handle it as the change may affect ongoing print jobs.
                        UserState userState = getOrCreateUserStateLocked(getChangingUserId());
                        boolean stoppedSomePackages = false;
                        Iterator<PrintServiceInfo> iterator = userState.getEnabledPrintServices()
                                .iterator();
                        while (iterator.hasNext()) {
                            ComponentName componentName = iterator.next().getComponentName();
                            String componentPackage = componentName.getPackageName();
                            for (String stoppedPackage : stoppedPackages) {
                                if (componentPackage.equals(stoppedPackage)) {
                                    if (!doit) {
                                        return true;
                                    }
                                    stoppedSomePackages = true;
                                    break;
                                }
                            }
                        }
                        if (stoppedSomePackages) {
                            userState.updateIfNeededLocked();
                        }
                        return false;
                    }
                }

                @Override
                public void onPackageAdded(String packageName, int uid) {
                    if (!mUserManager.isUserUnlocked(getChangingUserId())) return;

                    // A background user/profile's print jobs are running but there is
                    // no UI shown. Hence, if the packages of such a user change we need
                    // to handle it as the change may affect ongoing print jobs.
                    Intent intent = new Intent(android.printservice.PrintService.SERVICE_INTERFACE);
                    intent.setPackage(packageName);

                    List<ResolveInfo> installedServices = mContext.getPackageManager()
                            .queryIntentServicesAsUser(intent, PackageManager.GET_SERVICES,
                                    getChangingUserId());

                    if (installedServices != null) {
                        UserState userState = getOrCreateUserStateLocked(getChangingUserId());
                        userState.updateIfNeededLocked();
                    }
                }
            };

            // package changes
            monitor.register(mContext, BackgroundThread.getHandler().getLooper(),
                    UserHandle.ALL, true);
        }

        private UserState getOrCreateUserStateLocked(int userId) {
            if (!mUserManager.isUserUnlocked(userId)) {
                throw new IllegalStateException(
                        "User " + userId + " must be unlocked for printing to be available");
            }

            UserState userState = mUserStates.get(userId);
            if (userState == null) {
                userState = new UserState(mContext, userId, mLock);
                mUserStates.put(userId, userState);
            }
            return userState;
        }

        private void handleUserUnlocked(final int userId) {
            // This code will touch the remote print spooler which
            // must be called off the main thread, so post the work.
            BackgroundThread.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    UserState userState;
                    synchronized (mLock) {
                        userState = getOrCreateUserStateLocked(userId);
                        userState.updateIfNeededLocked();
                    }
                    // This is the first time we switch to this user after boot, so
                    // now is the time to remove obsolete print jobs since they
                    // are from the last boot and no application would query them.
                    userState.removeObsoletePrintJobs();
                }
            });
        }

        private void handleUserStopped(final int userId) {
            // This code will touch the remote print spooler which
            // must be called off the main thread, so post the work.
            BackgroundThread.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    synchronized (mLock) {
                        UserState userState = mUserStates.get(userId);
                        if (userState != null) {
                            userState.destroyLocked();
                            mUserStates.remove(userId);
                        }
                    }
                }
            });
        }

        private int resolveCallingProfileParentLocked(int userId) {
            if (userId != getCurrentUserId()) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    UserInfo parent = mUserManager.getProfileParent(userId);
                    if (parent != null) {
                        return parent.getUserHandle().getIdentifier();
                    } else {
                        return BACKGROUND_USER_ID;
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return userId;
        }

        private int resolveCallingAppEnforcingPermissions(int appId) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid == 0 || callingUid == Process.SYSTEM_UID
                    || callingUid == Process.SHELL_UID) {
                return appId;
            }
            final int callingAppId = UserHandle.getAppId(callingUid);
            if (appId == callingAppId) {
                return appId;
            }
            if (mContext.checkCallingPermission(
                    "com.android.printspooler.permission.ACCESS_ALL_PRINT_JOBS")
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Call from app " + callingAppId + " as app "
                        + appId + " without com.android.printspooler.permission"
                        + ".ACCESS_ALL_PRINT_JOBS");
            }
            return appId;
        }

        private int resolveCallingUserEnforcingPermissions(int userId) {
            try {
                return ActivityManagerNative.getDefault().handleIncomingUser(Binder.getCallingPid(),
                        Binder.getCallingUid(), userId, true, true, "", null);
            } catch (RemoteException re) {
                // Shouldn't happen, local.
            }
            return userId;
        }

        private String resolveCallingPackageNameEnforcingSecurity(String packageName) {
            if (TextUtils.isEmpty(packageName)) {
                return null;
            }
            String[] packages = mContext.getPackageManager().getPackagesForUid(
                    Binder.getCallingUid());
            final int packageCount = packages.length;
            for (int i = 0; i < packageCount; i++) {
                if (packageName.equals(packages[i])) {
                    return packageName;
                }
            }
            return null;
        }

        private int getCurrentUserId () {
            final long identity = Binder.clearCallingIdentity();
            try {
                return ActivityManager.getCurrentUser();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
