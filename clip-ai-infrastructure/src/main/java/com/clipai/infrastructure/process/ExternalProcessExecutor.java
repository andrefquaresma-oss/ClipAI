package com.clipai.infrastructure.process;

import java.time.Duration;
import java.util.List;

public interface ExternalProcessExecutor {
    ProcessResult execute(List<String> arguments, Duration timeout);
}
