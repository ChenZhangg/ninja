package ninja;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NWatchAndTerminate {
    
    private static Logger logger = LoggerFactory.getLogger(NWatchAndTerminate.class);

    
    RevolverSingleBullet revolver;

    WatchService watcher;

    AtomicInteger atomicInteger = new AtomicInteger(0);

    private final Map<WatchKey, Path> keys;
    private final boolean recursive;
    private boolean trace = false;

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }

    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE,
                ENTRY_MODIFY);
        if (trace) {
            Path prev = keys.get(key);
            if (prev == null) {
                System.out.format("register: %s\n", dir);
            } else {
                if (!dir.equals(prev)) {
                    System.out.format("update: %s -> %s\n", prev, dir);
                }
            }
        }
        keys.put(key, dir);

    }

    /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                                                     BasicFileAttributes attrs)
                    throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Creates a WatchService and registers the given directory
     */
    public NWatchAndTerminate(Path dir,
                              boolean recursive,
                              String clazz,
                              List<String> classpath) throws IOException {

        this.revolver = new RevolverSingleBullet(clazz, classpath);

        RestartAfterSomeTimeAndChanges restartAfterSomeTimeAndChanges = new RestartAfterSomeTimeAndChanges(
                atomicInteger, revolver);
        restartAfterSomeTimeAndChanges.start();

        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<WatchKey, Path>();
        this.recursive = recursive;

        if (recursive) {
            System.out.format("Scanning1 %s ...\n", dir);
            registerAll(dir);
            System.out.println("Done.");
        } else {
            System.out.format("Scanning non recursive %s ...\n", dir);
            register(dir);
        }

        // enable trace after initial registration
        this.trace = true;

    }

    /**
     * Process all events for keys queued to the watcher
     */
    public void processEvents() {

        for (;;) {

            // wait for key to be signalled
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                return;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                System.err.println("WatchKey not recognized!!");
                continue;
            }

            // revolver.pullTheTrigger();
            System.out.println("reload...");
            atomicInteger.incrementAndGet();

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind kind = event.kind();

                // TBD - provide example of how OVERFLOW event is handled
                if (kind == OVERFLOW) {
                    continue;
                }

                // Context for directory entry event is the file name of entry
                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                Path child = dir.resolve(name);

                // print out event
                System.out.format("%s: %s\n", event.kind().name(), child);

                // revolver.pullTheTrigger();

                // if directory is created, and watching recursively, then
                // register it and its sub-directories
                if (recursive && (kind == ENTRY_CREATE)) {
                    try {
                        if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                            registerAll(child);
                        }
                    } catch (IOException x) {
                        // ignore to keep sample readable
                    }

                }

            }

            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);

                // all directories are inaccessible
                if (keys.isEmpty()) {
                    break;
                }

            }

        }

    }

    private static class RestartAfterSomeTimeAndChanges extends Thread {

        AtomicInteger atomicInteger;
        private RevolverSingleBullet revolver;

        private RestartAfterSomeTimeAndChanges(AtomicInteger atomicInteger,
                                               RevolverSingleBullet revolver) {
            this.atomicInteger = atomicInteger;
            this.revolver = revolver;

        }

        @Override
        public void run() {

            while (true) {
                try {

                    sleep(50);
                    if (atomicInteger.get() > 0) {
                        atomicInteger.set(0);

                        System.out.println("restarting really...");
                        revolver.pullTheTrigger();

                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        }
    }

}
