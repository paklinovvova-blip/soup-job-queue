package dev.soup.queue.worker;

/**
 * How the worker leaves the process. Production exits for real (Docker restarts the container);
 * tests replace it to observe the exit without killing the test JVM.
 */
public interface Exiter {

    /** Orderly exit, used by the watchdog when an execution ignores a stop request. */
    void exit(int code);

    /** Immediate halt without shutdown hooks, like a crash. Used by the {@code simulate=crash} test hook. */
    void halt(int code);

    Exiter SYSTEM = new Exiter() {
        @Override
        public void exit(int code) {
            System.exit(code);
        }

        @Override
        public void halt(int code) {
            Runtime.getRuntime().halt(code);
        }
    };
}
