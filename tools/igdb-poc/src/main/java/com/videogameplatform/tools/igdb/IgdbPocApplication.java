package com.videogameplatform.tools.igdb;

import java.util.List;

import com.videogameplatform.tools.igdb.support.SecretRedactor;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "igdb-poc",
        mixinStandardHelpOptions = true,
        description = "Disposable authenticated IGDB provider evaluation.",
        subcommands = {SmokeCommand.class, RunCommand.class, ValidateCommand.class})
public final class IgdbPocApplication implements Runnable {

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new IgdbPocApplication());
        commandLine.setParameterExceptionHandler((exception, arguments) -> {
            System.err.println(exception.getMessage());
            return 5;
        });
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            String safe = SecretRedactor.redact(
                    exception.getMessage(),
                    List.of(
                            safe(System.getenv("IGDB_CLIENT_ID")),
                            safe(System.getenv("IGDB_CLIENT_SECRET"))));
            System.err.println("PoC error: " + safe);
            return exception instanceof IllegalArgumentException ? 5 : 4;
        });
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
