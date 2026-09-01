package io.jcordis.cli;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Command-line entry for jcordis, mirroring {@code create-cordis}.
 *
 * <p>Usage: {@code jcordis create <name> [target]} — scaffolds a new jcordis
 * application under the target directory (default: current directory).
 */
public final class Cli {

    private Cli() {}

    public static void main(String[] args) {
        int code = run(args);
        System.exit(code);
    }

    static int run(String[] args) {
        if (args.length < 2 || !args[0].equals("create")) {
            System.err.println("usage: jcordis create <name> [target]");
            return 1;
        }
        String name = args[1];
        if (!name.matches("[a-zA-Z0-9._-]+")) {
            System.err.println("invalid project name: " + name);
            return 1;
        }
        Path target = args.length > 2 ? Path.of(args[2]) : Path.of(".");
        try {
            Path dir = Scaffolder.create(name, target);
            System.out.println("created " + dir);
            return 0;
        } catch (IOException e) {
            System.err.println("failed to create project: " + e.getMessage());
            return 1;
        }
    }
}
