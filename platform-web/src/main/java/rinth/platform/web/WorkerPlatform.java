package rinth.platform.web;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import rinth.platform.api.AppInfo;
import rinth.platform.api.NativeSurfaceFactory;
import rinth.platform.api.Platform;
import rinth.platform.api.PlatformShell;
import rinth.platform.api.audio.PlatformAudio;
import rinth.platform.api.input.PlatformInput;
import rinth.platform.api.net.NetConnection;
import rinth.platform.api.net.PlatformNetwork;
import rinth.platform.api.profile.PlatformProfiling;
import rinth.platform.api.storage.PlatformStorage;
import rinth.platform.api.threads.PlatformChannel;
import rinth.platform.api.threads.PlatformThreads;
import rinth.platform.api.window.PlatformDisplays;
import rinth.platform.api.window.PlatformEventListener;
import rinth.platform.api.window.PlatformWindow;
import rinth.platform.api.window.WindowSpec;
import rinth.platform.web.storage.WebFileSystem;

/**
 * The platform in a copy of the game started in a worker (ADR 0039): singleplayer's server, which
 * needs threads, files and its page, and none of a window, sound or the network. The background
 * pool runs in slots a message loop hands out, as there are no frames here; the server's own thread
 * runs whenever the pool's tasks wait or a slot ends. Files are the worker's own tree, its changes
 * going to the page ({@link WebFileSystem#installDetached}).
 */
public final class WorkerPlatform implements Platform {

    private final PlatformStorage storage;
    private final WebFileSystem files;
    private final @Nullable PlatformChannel parent = JsChannel.parent();
    private final WorkerProfiling profiling = new WorkerProfiling();

    private final PlatformThreads threads = new PlatformThreads() {
        @Override
        public int backgroundThreads(int suggested) {
            return 0;
        }

        @Override
        public ExecutorService backgroundExecutor(String name) {
            return IdleTimeExecutor.forWorker("Worker-" + name);
        }

        @Override
        public void pauseIfLate() {
            IdleTimeExecutor.pauseIfLate();
        }

        @Override
        public boolean oneThread() {
            return true;
        }

        @Override
        public int backgroundQueued() {
            return IdleTimeExecutor.queuedEverywhere();
        }

        @Override
        public @Nullable PlatformChannel parentChannel() {
            return parent;
        }

        @Override
        public @Nullable PlatformChannel startGameWorker(String role) {
            return JsChannel.startWorker(role);
        }
    };

    /**
     * @param folders where the game's folders are (the same as the page's)
     * @param files the worker's own file system
     */
    public WorkerPlatform(PlatformStorage folders, WebFileSystem files) {
        this.storage = new FileStorage(folders, files);
        this.files = files;
    }

    /**
     * Reads a zip the page's storage keeps (the client jar) straight from the origin's private file
     * system, where the storage worker put it; else fetches it from {@code url}, as the page did
     * the first time. Suspends until it's read.
     *
     * @param cachePath where the storage worker keeps it, from the private file system's root
     */
    @Async
    public static native ArrayBuffer cachedZip(String cachePath, String url);

    private static void cachedZip(String cachePath, String url, AsyncCallback<ArrayBuffer> callback) {
        readCachedZip(cachePath, url, buffer -> callback.complete(buffer));
    }

    @JSFunctor
    interface BufferRead extends JSObject {
        void read(ArrayBuffer buffer);
    }

    @JSBody(
            params = {"cachePath", "url", "done"},
            script = "function fetchIt() { return fetch(url).then(function(r) {"
                    + "  if (!r.ok) throw new Error(url + ': ' + r.status); return r.arrayBuffer(); }); }"
                    + "var read = navigator.storage && navigator.storage.getDirectory"
                    + "  ? navigator.storage.getDirectory().then(function(dir) {"
                    + "      var parts = cachePath.split('/').filter(function(p) { return p; });"
                    + "      var file = parts.pop(); var step = Promise.resolve(dir);"
                    + "      parts.forEach(function(p) { step = step.then(function(d) { return d.getDirectoryHandle(p); }); });"
                    + "      return step.then(function(d) { return d.getFileHandle(file); })"
                    + "        .then(function(h) { return h.getFile(); }).then(function(f) { return f.arrayBuffer(); }); })"
                    + "  : Promise.reject(new Error('no private file system'));"
                    + "read.catch(fetchIt).then(done, function(error) {"
                    + "  console.error('[Rinth] Worker: no ' + url + ': ' + error); done(new ArrayBuffer(0)); });")
    private static native void readCachedZip(String cachePath, String url, BufferRead done);

    @Override
    public String description() {
        return "Browser worker";
    }

    @Override
    public PlatformProfiling profiling() {
        return profiling;
    }

    @Override
    public void initialize(AppInfo app) {}

    @Override
    public void shutdown() {
        files.flush();
    }

    @Override
    public long nanoTime() {
        return (long) (Dom.nowMillis() * 1_000_000.0);
    }

    @Override
    public PlatformDisplays displays() {
        throw new UnsupportedOperationException("A worker has no displays");
    }

    @Override
    public PlatformWindow createWindow(WindowSpec spec, NativeSurfaceFactory surface) {
        throw new UnsupportedOperationException("A worker has no window");
    }

    @Override
    public void pollEvents(PlatformEventListener listener) {}

    @Override
    public void endFrame() {}

    @Override
    public void flushInputEvents() {}

    @Override
    public PlatformInput input() {
        throw new UnsupportedOperationException("A worker has no input");
    }

    @Override
    public PlatformShell shell() {
        return new PlatformShell() {
            @Override
            public String clipboardText() {
                return "";
            }

            @Override
            public boolean setClipboardText(String text) {
                return false;
            }

            @Override
            public void open(URI uri) {}

            @Override
            public boolean showErrorDialog(String title, String message, boolean askToContinue) {
                Dom.consoleError("[Rinth] " + title + ": " + message);
                return false;
            }
        };
    }

    @Override
    public PlatformAudio audio() {
        throw new UnsupportedOperationException("A worker has no sound");
    }

    @Override
    public PlatformNetwork network() {
        return new PlatformNetwork() {
            @Override
            public NetConnection connect(String host, int port, NetConnection.Listener listener) {
                throw new UnsupportedOperationException("A worker's copy of the game connects nowhere");
            }

            @Override
            public NetConnection openWebSocket(String url, NetConnection.Listener listener) {
                throw new UnsupportedOperationException("A worker's copy of the game connects nowhere");
            }

            @Override
            public boolean canAcceptConnections() {
                return false;
            }
        };
    }

    @Override
    public void crashNatively() {
        throw new InternalError("Debug crash requested; a worker can't crash natively");
    }

    @Override
    public PlatformStorage storage() {
        return storage;
    }

    @Override
    public PlatformThreads threads() {
        return threads;
    }
}
