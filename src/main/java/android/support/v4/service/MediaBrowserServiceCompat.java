/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.support.v4.service;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.content.pm.ParceledListSliceCompat;
import android.support.v4.media.browse.MediaBrowserCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.util.ArrayMap;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;

/**
 * Base class for media browse services.
 * <p>
 * Media browse services enable applications to browse media content provided by an application
 * and ask the application to start playing it.  They may also be used to control content that
 * is already playing by way of a {@link MediaSessionCompat}.
 * </p>
 *
 * To extend this class, you must declare the service in your manifest file with
 * an intent filter with the {@link #SERVICE_INTERFACE} action.
 *
 * For example:
 * </p><pre>
 * &lt;service android:name=".MyMediaBrowserService"
 *          android:label="&#64;string/service_name" >
 *     &lt;intent-filter>
 *         &lt;action android:name="android.media.browse.MediaBrowserServiceCompat" />
 *     &lt;/intent-filter>
 * &lt;/service>
 * </pre>
 *
 */
public abstract class MediaBrowserServiceCompat extends Service {

    private static final String TAG ="MediaBrowserServiceCompat";

    private static final boolean DBG = false;

    /**
     * The {@link android.content.Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE = "android.media.browse.MediaBrowserServiceCompat";

    private final ArrayMap<IBinder, ConnectionRecord> mConnections = new ArrayMap();
    private final Handler mHandler = new Handler();
    private ServiceBinder mBinder;
    MediaSessionCompat.Token mSession;

    /**
     * All the info about a connection.
     */
    private class ConnectionRecord {
        String pkg;
        Bundle rootHints;
        IMediaBrowserServiceCompatCallbacks callbacks;
        BrowserRoot root;
        HashSet<String> subscriptions = new HashSet();
    }

    /**
     * Completion handler for asynchronous callback methods in {@link MediaBrowserServiceCompat}.
     * <p>
     * Each of the methods that takes one of these to send the result must call
     * {@link #sendResult} to respond to the caller with the given results.  If those
     * functions return without calling {@link #sendResult}, they must instead call
     * {@link #detach} before returning, and then may call {@link #sendResult} when
     * they are done.  If more than one of those methods is called, an exception will
     * be thrown.
     *
     * @see MediaBrowserServiceCompat#onLoadChildren
     */
    public class Result<T> {
        private Object mDebug;
        private boolean mDetachCalled;
        private boolean mSendResultCalled;

        Result(Object debug) {
            mDebug = debug;
        }

        /**
         * Send the result back to the caller.
         */
        public void sendResult(T result) {
            if (mSendResultCalled) {
                throw new IllegalStateException("sendResult() called twice for: " + mDebug);
            }
            mSendResultCalled = true;
            onResultSent(result);
        }

        /**
         * Detach this message from the current thread and allow the {@link #sendResult}
         * call to happen later.
         */
        public void detach() {
            if (mDetachCalled) {
                throw new IllegalStateException("detach() called when detach() had already"
                        + " been called for: " + mDebug);
            }
            if (mSendResultCalled) {
                throw new IllegalStateException("detach() called when sendResult() had already"
                        + " been called for: " + mDebug);
            }
            mDetachCalled = true;
        }

        boolean isDone() {
            return mDetachCalled || mSendResultCalled;
        }

        /**
         * Called when the result is sent, after assertions about not being called twice
         * have happened.
         */
        void onResultSent(T result) {
        }
    }

    private class ServiceBinder extends IMediaBrowserServiceCompat.Stub {
        @Override
        public void connect(final String pkg, final Bundle rootHints,
                            final IMediaBrowserServiceCompatCallbacks callbacks) {

            final int uid = Binder.getCallingUid();
            if (!isValidPackage(pkg, uid)) {
                throw new IllegalArgumentException("Package/uid mismatch: uid=" + uid
                        + " package=" + pkg);
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    final IBinder b = callbacks.asBinder();

                    // Clear out the old subscriptions.  We are getting new ones.
                    mConnections.remove(b);

                    final ConnectionRecord connection = new ConnectionRecord();
                    connection.pkg = pkg;
                    connection.rootHints = rootHints;
                    connection.callbacks = callbacks;

                    connection.root = MediaBrowserServiceCompat.this.onGetRoot(pkg, uid, rootHints);

                    // If they didn't return something, don't allow this client.
                    if (connection.root == null) {
                        Log.i(TAG, "No root for client " + pkg + " from service "
                                + getClass().getName());
                        try {
                            callbacks.onConnectFailed();
                        } catch (RemoteException ex) {
                            Log.w(TAG, "Calling onConnectFailed() failed. Ignoring. "
                                    + "pkg=" + pkg);
                        }
                    } else {
                        try {
                            mConnections.put(b, connection);
                            callbacks.onConnect(connection.root.getRootId(),
                                    mSession, connection.root.getExtras());
                        } catch (RemoteException ex) {
                            Log.w(TAG, "Calling onConnect() failed. Dropping client. "
                                    + "pkg=" + pkg);
                            mConnections.remove(b);
                        }
                    }
                }
            });
        }

        @Override
        public void disconnect(final IMediaBrowserServiceCompatCallbacks callbacks) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    final IBinder b = callbacks.asBinder();

                    // Clear out the old subscriptions.  We are getting new ones.
                    final ConnectionRecord old = mConnections.remove(b);
                    if (old != null) {
                        // TODO
                    }
                }
            });
        }


        @Override
        public void addSubscription(final String id, final IMediaBrowserServiceCompatCallbacks callbacks) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    final IBinder b = callbacks.asBinder();

                    // Get the record for the connection
                    final ConnectionRecord connection = mConnections.get(b);
                    if (connection == null) {
                        Log.w(TAG, "addSubscription for callback that isn't registered id="
                                + id);
                        return;
                    }

                    MediaBrowserServiceCompat.this.addSubscription(id, connection);
                }
            });
        }

        @Override
        public void removeSubscription(final String id,
                                       final IMediaBrowserServiceCompatCallbacks callbacks) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    final IBinder b = callbacks.asBinder();

                    ConnectionRecord connection = mConnections.get(b);
                    if (connection == null) {
                        Log.w(TAG, "removeSubscription for callback that isn't registered id="
                                + id);
                        return;
                    }
                    if (!connection.subscriptions.remove(id)) {
                        Log.w(TAG, "removeSubscription called for " + id
                                + " which is not subscribed");
                    }
                }
            });
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mBinder = new ServiceBinder();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    }

    /**
     * Called to get the root information for browsing by a particular client.
     * <p>
     * The implementation should verify that the client package has
     * permission to access browse media information before returning
     * the root id; it should return null if the client is not
     * allowed to access this information.
     * </p>
     *
     * @param clientPackageName The package name of the application
     * which is requesting access to browse media.
     * @param clientUid The uid of the application which is requesting
     * access to browse media.
     * @param rootHints An optional bundle of service-specific arguments to send
     * to the media browse service when connecting and retrieving the root id
     * for browsing, or null if none.  The contents of this bundle may affect
     * the information returned when browsing.
     */
    public abstract BrowserRoot onGetRoot(String clientPackageName,
                                                    int clientUid, Bundle rootHints);

    /**
     * Called to get information about the children of a media item.
     * <p>
     * Implementations must call result.{@link Result#sendResult result.sendResult} with the list
     * of children. If loading the children will be an expensive operation that should be performed
     * on another thread, result.{@link Result#detach result.detach} may be called before returning
     * from this function, and then {@link Result#sendResult result.sendResult} called when
     * the loading is complete.
     *
     * @param parentId The id of the parent media item whose
     * children are to be queried.
     * @return The list of children, or null if the id is invalid.
     */
    public abstract void onLoadChildren(String parentId,
                                        Result<List<MediaBrowserCompat.MediaItem>> result);

    /**
     * Call to set the media session.
     * <p>
     * This must be called before onCreate returns.
     *
     * @return The media session token, must not be null.
     */
    public void setSessionToken(MediaSessionCompat.Token token) {
        if (token == null) {
            throw new IllegalStateException(this.getClass().getName()
                    + ".onCreateSession() set invalid MediaSession.Token");
        }
        mSession = token;
    }

    /**
     * Gets the session token, or null if it has not yet been created
     * or if it has been destroyed.
     */
    public MediaSessionCompat.Token getSessionToken() {
        return mSession;
    }

    /**
     * Notifies all connected media browsers that the children of
     * the specified parent id have changed in some way.
     * This will cause browsers to fetch subscribed content again.
     *
     * @param parentId The id of the parent media item whose
     * children changed.
     */
    public void notifyChildrenChanged(final String parentId) {
        if (parentId == null) {
            throw new IllegalArgumentException("parentId cannot be null in notifyChildrenChanged");
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IBinder binder : mConnections.keySet()) {
                    ConnectionRecord connection = mConnections.get(binder);
                    if (connection.subscriptions.contains(parentId)) {
                        performLoadChildren(parentId, connection);
                    }
                }
            }
        });
    }

    /**
     * Return whether the given package is one of the ones that is owned by the uid.
     */
    private boolean isValidPackage(String pkg, int uid) {
        if (pkg == null) {
            return false;
        }
        final PackageManager pm = getPackageManager();
        final String[] packages = pm.getPackagesForUid(uid);
        final int N = packages.length;
        for (int i=0; i<N; i++) {
            if (packages[i].equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Save the subscription and if it is a new subscription send the results.
     */
    private void addSubscription(String id, ConnectionRecord connection) {
        // Save the subscription
        final boolean added = connection.subscriptions.add(id);

        // If this is a new subscription, send the results
        if (added) {
            performLoadChildren(id, connection);
        }
    }

    /**
     * Call onLoadChildren and then send the results back to the connection.
     * <p>
     * Callers must make sure that this connection is still connected.
     */
    private void performLoadChildren(final String parentId, final ConnectionRecord connection) {

        final Result<List<MediaBrowserCompat.MediaItem>> result  = new Result<List<MediaBrowserCompat.MediaItem>>(parentId) {

            @Override
            void onResultSent(List<MediaBrowserCompat.MediaItem> list) {
                if (list == null) {
                    throw new IllegalStateException("onLoadChildren sent null list for id "
                            + parentId);
                }
                if (mConnections.get(connection.callbacks.asBinder()) != connection) {
                    if (DBG) {
                        Log.d(TAG, "Not sending onLoadChildren result for connection that has"
                                + " been disconnected. pkg=" + connection.pkg + " id=" + parentId);
                    }
                    return;
                }

                final ParceledListSliceCompat<MediaBrowserCompat.MediaItem> pls = new ParceledListSliceCompat(list);

                try {
                    connection.callbacks.onLoadChildren(parentId, pls);
                } catch (RemoteException ex) {
                    // The other side is in the process of crashing.
                    Log.w(TAG, "Calling onLoadChildren() failed for id=" + parentId + " package=" + connection.pkg);
                }
            }
        };

        onLoadChildren(parentId, result);

        if (!result.isDone()) {
            throw new IllegalStateException("onLoadChildren must call detach() or sendResult()"
                    + " before returning for package=" + connection.pkg + " id=" + parentId);
        }
    }

    /**
     * Contains information that the browser service needs to send to the client
     * when first connected.
     */
    public static final class BrowserRoot {
        final private String mRootId;
        final private Bundle mExtras;

        /**
         * Constructs a browser root.
         * @param rootId The root id for browsing.
         * @param extras Any extras about the browser service.
         */
        public BrowserRoot(String rootId, Bundle extras) {
            if (rootId == null) {
                throw new IllegalArgumentException("The root id in BrowserRoot cannot be null. " +
                        "Use null for BrowserRoot instead.");
            }
            mRootId = rootId;
            mExtras = extras;
        }

        /**
         * Gets the root id for browsing.
         */
        public String getRootId() {
            return mRootId;
        }

        /**
         * Gets any extras about the brwoser service.
         */
        public Bundle getExtras() {
            return mExtras;
        }
    }
}
