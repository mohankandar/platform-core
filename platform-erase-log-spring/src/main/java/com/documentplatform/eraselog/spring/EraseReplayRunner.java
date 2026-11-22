package com.documentplatform.eraselog.spring;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseReplay;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import java.io.PrintStream;
import java.util.function.IntConsumer;
import java.lang.System.Logger.Level;

/**
 * The replay command: start the app with {@value #OPTION} (for example
 * {@code java -jar app.jar --erase-replay}) after restoring the database. It runs the replay with the app's handlers
 * and the verify step ({@link EraseReplay}), prints the report (counts, codes and entry numbers only), and — unless
 * {@code platform.erase-log.exit-after-replay=false} — exits: 0 when the replay passed and the guard lifted, 1 when
 * not. Running it again is safe. The guard keeps the app from taking work during the run.
 */
public final class EraseReplayRunner implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPTION = "erase-replay";

    private static final System.Logger LOG = System.getLogger(EraseReplayRunner.class.getName());

    private final EraseReplay replay;
    private final EraseGuard guard;
    private final ApplicationContext context;
    private final boolean exit;
    private final PrintStream out;
    private final IntConsumer exitProcess;
    private volatile int exitCode;
    private volatile EraseReplay.Report last;

    public EraseReplayRunner(EraseReplay replay, EraseGuard guard, ApplicationContext context, boolean exit) {
        this(replay, guard, context, exit, System.out, System::exit);
    }

    EraseReplayRunner(EraseReplay replay, EraseGuard guard, ApplicationContext context, boolean exit, PrintStream out,
                      IntConsumer exitProcess) {
        this.replay = replay;
        this.guard = guard;
        this.context = context;
        this.exit = exit;
        this.out = out;
        this.exitProcess = exitProcess;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        EraseReplay.Report report = replay.run();
        last = report;
        exitCode = report.passed() ? 0 : 1;
        guard.refresh();
        out.println(report.describe());
        LOG.log(report.passed() ? Level.INFO : Level.WARNING, "Erase replay: {0}, {1} applied, {2} verified",
                report.passed() ? "passed" : report.code(), report.appliedCount(), report.verified());
        if (exit) {
            exitProcess.accept(SpringApplication.exit(context, this));
        }
    }

    /** The last run's report, if the replay ran. */
    public EraseReplay.Report lastReport() {
        return last;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
