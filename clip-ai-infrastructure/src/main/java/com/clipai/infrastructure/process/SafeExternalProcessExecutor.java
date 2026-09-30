package com.clipai.infrastructure.process;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class SafeExternalProcessExecutor implements ExternalProcessExecutor {
    private static final int MAX_CAPTURED_CHARS = 65_536;
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);

    @Override
    public ProcessResult execute(List<String> arguments, Duration timeout) {
        if (arguments == null || arguments.isEmpty() || timeout == null || timeout.isNegative()
                || timeout.isZero()) {
            throw new IllegalArgumentException("Process arguments and a positive timeout are required");
        }

        long startedAt = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(List.copyOf(arguments)).start();
        } catch (IOException exception) {
            throw new ProcessExecutionException("Unable to start configured media process", exception);
        }

        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> stdout = readers.submit(() -> capture(process.getInputStream()));
            Future<String> stderr = readers.submit(() -> capture(process.getErrorStream()));
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                terminate(process);
                Thread.currentThread().interrupt();
                throw new ProcessExecutionException("Media process was interrupted", exception);
            }
            if (!finished) {
                terminate(process);
                throw new ProcessExecutionException("Media process exceeded its configured timeout");
            }
            return new ProcessResult(process.exitValue(), getOutput(stdout), getOutput(stderr),
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (ExecutionException exception) {
            terminate(process);
            throw new ProcessExecutionException("Unable to collect media process output", exception.getCause());
        }
    }

    private static String getOutput(Future<String> output) throws ExecutionException {
        try {
            return output.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProcessExecutionException("Interrupted while collecting media process output", exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new ProcessExecutionException("Timed out while collecting media process output", exception);
        }
    }

    private static String capture(InputStream stream) throws IOException {
        StringBuilder captured = new StringBuilder();
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                int remaining = MAX_CAPTURED_CHARS - captured.length();
                if (remaining > 0) {
                    captured.append(buffer, 0, Math.min(count, remaining));
                }
            }
        }
        return captured.toString();
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroy();
        try {
            if (!process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
