package com.documentplatform.eraselog.spring;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.Seal;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.util.function.IntConsumer;

/**
 * The one-time migration of an existing erase log to a keyed one (P044, SEC-3): start the app once with
 * {@value #OPTION} (for example {@code java -jar app.jar --erase-log-seal}). It checks the whole chain, then seals the
 * log at its head with the log's key ({@link EraseLog#seal()}): the entries so far are fixed through the hash chain, and
 * every later entry needs a MAC. It writes the seal only and rewrites no entry; running it again is safe. It prints the
 * result (entry numbers and codes only) and, unless {@code platform.erase-log.exit-after-replay=false}, exits: 0 when
 * the log is sealed, 1 when not (a broken chain is never sealed: restore the log from its own backup first).
 */
public final class EraseLogSealRunner implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPTION = "erase-log-seal";

    private static final System.Logger LOG = System.getLogger(EraseLogSealRunner.class.getName());

    private final EraseLog log;
    private final EraseGuard guard;
    private final ApplicationContext context;
    private final boolean exit;
    private final PrintStream out;
    private final IntConsumer exitProcess;
    private volatile int exitCode;

    public EraseLogSealRunner(EraseLog log, EraseGuard guard, ApplicationContext context, boolean exit) {
        this(log, guard, context, exit, System.out, System::exit);
    }

    EraseLogSealRunner(EraseLog log, EraseGuard guard, ApplicationContext context, boolean exit, PrintStream out,
                       IntConsumer exitProcess) {
        this.log = log;
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
        try {
            Seal seal = log.seal();
            exitCode = 0;
            out.println("Erase log sealed: entries 1 to " + (seal.from() - 1) + " are fixed by the seal; every entry "
                    + "from " + seal.from() + " on carries a MAC (log " + seal.logId() + ")");
            LOG.log(Level.INFO, "Erase log {0} sealed from entry {1}", seal.logId(), seal.from());
        } catch (EraseLogException e) {
            exitCode = 1;
            out.println("Erase log NOT sealed: " + e.code() + ": " + e.reason());
            LOG.log(Level.WARNING, "Erase log not sealed: {0}", e.code());
        }
        guard.fullCheck();
        if (exit) {
            exitProcess.accept(SpringApplication.exit(context, this));
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
