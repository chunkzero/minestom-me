package net.minestom.server.exception;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Manages the handling of exceptions.
 */
public final class ExceptionManager {

    private final Runnable stopServer;

    public ExceptionManager(Runnable stopServer) {
        this.stopServer = Objects.requireNonNull(stopServer);
    }

    private @Nullable ExceptionHandler exceptionHandler;

    /**
     * Handles an exception, if no {@link ExceptionHandler} is set, it just prints the stack trace.
     *
     * @param e the occurred exception
     */
    public void handleException(Throwable e) {
        if (e instanceof OutOfMemoryError) {
            // OOM should be handled manually
            e.printStackTrace();
            stopServer.run();
            return;
        }
        this.getExceptionHandler().handleException(e);
    }

    /**
     * Changes the exception handler, to allow custom exception handling.
     *
     * @param exceptionHandler the new {@link ExceptionHandler}, can be set to null to apply the default provider
     */
    public void setExceptionHandler(@Nullable ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Retrieves the current {@link ExceptionHandler}, can be the default one if none is defined.
     *
     * @return the current {@link ExceptionHandler}
     */
    public ExceptionHandler getExceptionHandler() {
        if (this.exceptionHandler == null) this.exceptionHandler = Throwable::printStackTrace;
        return this.exceptionHandler;
    }
}
