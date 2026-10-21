///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * House Elves on your laptop: installs Elf Desk and its runner as services (systemd on Linux, launchd on macOS),
 * checks that everything they need is there and logged in (doctor), and opens the desk in your browser.
 */
@Command(name = "house-elves", mixinStandardHelpOptions = true, version = "house-elves 0.1.2",
        description = "Elf Desk and its elves on this machine.",
        subcommands = { HouseElves.Install.class, HouseElves.Doctor.class, HouseElves.Open.class, HouseElves.Remote.class,
                HouseElves.Start.class, HouseElves.Stop.class, HouseElves.Restart.class, HouseElves.Status.class,
                HouseElves.Logs.class, HouseElves.SignOut.class, HouseElves.Uninstall.class })
public class HouseElves {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String HOME = System.getProperty("user.home");
    static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    static final Path CONFIG = Path.of(System.getenv().getOrDefault("HOUSE_ELVES_CONFIG", HOME + "/.config/house-elves"));
    static final Path DATA = Path.of(System.getenv().getOrDefault("HOUSE_ELVES_DATA", HOME + "/.local/share/house-elves"));
    static final Path APP = DATA.resolve("app");
    static final String RELEASES = "https://github.com/House-elves/%s/releases/latest/download/%s";
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("HOUSE_ELVES_PORT", "7777"));
    /** The desk's own name (elfdesk.host): browsers send *.localhost to this machine, and its cookie stays there. */
    static String deskUrl() {
        String host;
        try {
            host = readProps(CONFIG.resolve("desk.properties")).getOrDefault("elfdesk.host", "elf-desk.localhost");
        } catch (IOException e) {
            host = "elf-desk.localhost";
        }
        return "http://" + host + ":" + PORT;
    }

    /** A second install beside the first (for trying a build): its services are named with this suffix. */
    static final String INSTANCE = System.getenv().getOrDefault("HOUSE_ELVES_INSTANCE", "");

    /** What the services need to find this install's config and data, when they aren't in the usual places. */
    static Map<String, String> serviceEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String k : List.of("HOUSE_ELVES_CONFIG", "HOUSE_ELVES_DATA")) {
            if (System.getenv(k) != null) env.put(k, System.getenv(k));
        }
        return env;
    }

    public static void main(String... args) {
        System.exit(new CommandLine(new HouseElves()).execute(args));
    }

    // ---- the two services ----

    /** The desk and the runner, as this OS runs them. */
    enum Service {
        DESK("house-elves-desk", "io.github.house-elves.desk", "Elf Desk"),
        RUNNER("house-elves-runner", "io.github.house-elves.runner", "Elf Desk runner (agents, GitHub writes)");

        final String unit;
        final String label;
        final String description;

        Service(String unit, String label, String description) {
            this.unit = unit + (INSTANCE.isEmpty() ? "" : "-" + INSTANCE);
            this.label = label + (INSTANCE.isEmpty() ? "" : "." + INSTANCE);
            this.description = description;
        }

        Path file() {
            return MAC ? Path.of(HOME, "Library/LaunchAgents", label + ".plist")
                    : Path.of(HOME, ".config/systemd/user", unit + ".service");
        }

        Path log() {
            return Path.of(HOME, "Library/Logs/house-elves", unit + ".log");
        }

        String target() {
            return "gui/" + uid() + "/" + label;
        }

        boolean installed() {
            return Files.isRegularFile(file());
        }

        boolean running() {
            if (MAC) return run(List.of("launchctl", "print", target())).out.contains("state = running");
            return run(List.of("systemctl", "--user", "is-active", unit)).out.strip().equals("active");
        }

        void start() {
            if (MAC) {
                if (!run(List.of("launchctl", "print", target())).ok()) {
                    check(run(List.of("launchctl", "bootstrap", "gui/" + uid(), file().toString())), "start " + unit);
                } else {
                    check(run(List.of("launchctl", "kickstart", target())), "start " + unit);
                }
            } else {
                check(run(List.of("systemctl", "--user", "enable", "--now", unit)), "start " + unit);
            }
        }

        void stop() {
            if (MAC) run(List.of("launchctl", "bootout", target()));
            else run(List.of("systemctl", "--user", "stop", unit));
        }

        void restart() {
            if (MAC) {
                stop();
                start();
            } else {
                check(run(List.of("systemctl", "--user", "restart", unit)), "restart " + unit);
            }
        }

        /** Writes the unit or plist for {@code command}, with the PATH the services need. */
        void write(List<String> command, String path, String memory) throws IOException {
            Files.createDirectories(file().getParent());
            String content;
            if (MAC) {
                Files.createDirectories(log().getParent());
                StringBuilder args = new StringBuilder();
                for (String a : command) args.append("    <string>").append(xml(a)).append("</string>\n");
                content = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                        <dict>
                          <key>Label</key><string>%s</string>
                          <key>ProgramArguments</key>
                          <array>
                        %s  </array>
                          <key>EnvironmentVariables</key>
                          <dict><key>PATH</key><string>%s</string>%s</dict>
                          <key>WorkingDirectory</key><string>%s</string>
                          <key>RunAtLoad</key><true/>
                          <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
                          <key>ThrottleInterval</key><integer>10</integer>
                          <key>StandardOutPath</key><string>%s</string>
                          <key>StandardErrorPath</key><string>%s</string>
                        </dict>
                        </plist>
                        """.formatted(label, args, xml(path), plistEnv(), xml(DATA.toString()), xml(log().toString()),
                        xml(log().toString()));
            } else {
                content = """
                        [Unit]
                        Description=%s
                        Wants=network-online.target
                        After=network-online.target%s

                        [Service]
                        Type=simple
                        WorkingDirectory=%s
                        Environment=PATH=%s
                        %sExecStart=%s
                        Restart=on-failure
                        RestartSec=10
                        SuccessExitStatus=143
                        %s
                        [Install]
                        WantedBy=default.target
                        """.formatted(description, this == RUNNER ? " " + DESK.unit + ".service" : "", DATA, path,
                        unitEnv(), String.join(" ", command.stream().map(HouseElves::systemdQuote).toList()), memory);
            }
            Files.writeString(file(), content);
        }

        static String unitEnv() {
            StringBuilder sb = new StringBuilder();
            serviceEnv().forEach((k, v) -> sb.append("Environment=").append(k).append('=').append(systemdQuote(v)).append('\n'));
            return sb.toString();
        }

        static String plistEnv() {
            StringBuilder sb = new StringBuilder();
            serviceEnv().forEach((k, v) -> sb.append("<key>").append(k).append("</key><string>").append(xml(v)).append("</string>"));
            return sb.toString();
        }
    }

    // ---- install ----

    @Command(name = "install", description = "Install Elf Desk and its runner on this machine, or update them.")
    static class Install implements Callable<Integer> {

        @Option(names = "--github-user", description = "The GitHub account the desk works as (one gh is logged in to).")
        String githubUser;

        @Option(names = "--git-name", description = "Name on commits the agent makes.")
        String gitName;

        @Option(names = "--git-email", description = "Email on commits the agent makes.")
        String gitEmail;

        @Option(names = "--desk", description = "A desk build to install (a quarkus-app directory or a zip) instead of the latest release.")
        Path desk;

        @Option(names = "--runner", description = "A runner build to install (a jar) instead of the latest release.")
        Path runner;

        @Option(names = "--no-open", description = "Don't open the desk when it's up.")
        boolean noOpen;

        @Option(names = "--claude-token-from-env", description = "Take the runner's Claude token from $CLAUDE_CODE_OAUTH_TOKEN instead of asking.")
        boolean claudeTokenFromEnv;

        @Override
        public Integer call() throws Exception {
            say("Checking this machine first.");
            if (!new Doctor().report(false)) {
                say("\nFix what's marked ✗ above, then run house-elves install again.");
                return 1;
            }
            Files.createDirectories(CONFIG);
            Files.createDirectories(APP);
            Map<String, String> desk = readProps(CONFIG.resolve("desk.properties"));
            Map<String, String> runner = readProps(CONFIG.resolve("runner.config"));

            // Who the desk works as.
            String user = githubUser != null ? githubUser : desk.get("elfdesk.github-user");
            if (user == null || user.isBlank()) user = pickAccount();
            if (user == null) return 1;
            JsonNode me = JSON.readTree(run(List.of("gh", "api", "user"), Map.of("GH_TOKEN", ghToken(user))).out);

            // Who the agent's commits are by.
            String name = gitName != null ? gitName : runner.getOrDefault("GIT_NAME",
                    ask("Name on commits the agent makes", me.path("name").asText(user)));
            String email = gitEmail != null ? gitEmail : runner.getOrDefault("GIT_EMAIL",
                    ask("Email on those commits", me.path("id").asLong() + "+" + user + "@users.noreply.github.com"));

            // The sandboxed Claude's login.
            String claudeToken = claudeTokenFromEnv ? System.getenv("CLAUDE_CODE_OAUTH_TOKEN") : runner.get("CLAUDE_CODE_OAUTH_TOKEN");
            if (claudeToken == null || claudeToken.isBlank()) {
                say("""

                        The agent runs Claude Code in a sandbox that can't see your normal Claude login. Give it a token of its own:
                          1. In another terminal, run: claude setup-token
                          2. Sign in when your browser opens, then copy the token it prints.""");
                claudeToken = secret("Paste the token here");
                if (claudeToken == null || claudeToken.isBlank()) {
                    say("No token: run house-elves install again when you have one.");
                    return 1;
                }
            }

            String runnerToken = runner.getOrDefault("RUNNER_TOKEN", desk.getOrDefault("elfdesk.runner.token", random()));
            desk.put("elfdesk.github-user", user);
            desk.put("elfdesk.runner.token", runnerToken);
            if (PORT != 7777) desk.put("quarkus.http.port", String.valueOf(PORT));
            else desk.remove("quarkus.http.port");
            writeProps(CONFIG.resolve("desk.properties"), desk,
                    "Elf Desk's settings (house-elves install). Secrets: keep this file to yourself.");
            runner.put("DESK_URL", "ws://127.0.0.1:" + PORT + "/runner");
            runner.put("RUNNER_TOKEN", runnerToken);
            runner.put("GITHUB_USER", user);
            runner.put("GIT_NAME", name);
            runner.put("GIT_EMAIL", email);
            runner.put("CLAUDE_CODE_OAUTH_TOKEN", claudeToken.strip());
            runner.putIfAbsent("MAX_JOBS", "2");
            runner.putIfAbsent("JOB_TIMEOUT_MINUTES", "45");
            runner.putIfAbsent("SANDBOX_PODMAN", "false");
            writeProps(CONFIG.resolve("runner.config"), runner,
                    "Elf Desk's runner (house-elves install). Secrets: keep this file to yourself.");

            // The programs.
            say("\nInstalling the desk and the runner in " + APP);
            installDesk();
            installRunner();

            // The services, with the PATH this shell has: gh, claude, git, mvn and podman are found the same way.
            String java = javaFor25();
            String path = Path.of(java).getParent() + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin");
            for (Service s : Service.values()) s.stop();
            Service.DESK.write(List.of(java, "-Xmx512m", "-jar", APP.resolve("desk/quarkus-run.jar").toString()), path, "");
            Service.RUNNER.write(List.of(java, "-Xmx256m", "-jar", APP.resolve("elf-runner.jar").toString()), path, """
                    # Agents run as child processes: stopping the runner stops them too. Their builds share this unit's
                    # memory; at MemoryMax only the process that went over dies, not the runner or the other agents.
                    KillMode=control-group
                    MemoryHigh=6G
                    MemoryMax=8G
                    OOMPolicy=continue
                    """);
            if (LINUX) check(run(List.of("systemctl", "--user", "daemon-reload")), "reload systemd");
            for (Service s : Service.values()) s.start();

            say("Starting the desk…");
            if (!waitForDesk(Duration.ofSeconds(90))) {
                say("The desk didn't come up. See: house-elves logs desk");
                return 1;
            }
            say("Elf Desk is up on " + deskUrl());
            if (!noOpen) return new Open().call();
            say("Open it with: house-elves open");
            return 0;
        }

        String pickAccount() throws IOException {
            List<String> logins = new ArrayList<>();
            for (JsonNode a : JSON.readTree(run(List.of("gh", "auth", "status", "--json", "hosts")).out)
                    .path("hosts").path("github.com")) {
                if (a.path("state").asText().equals("success")) logins.add(a.path("login").asText());
            }
            if (logins.size() == 1) return logins.get(0);
            say("\ngh is logged in to several GitHub accounts. Which one should the desk work as?");
            for (int i = 0; i < logins.size(); i++) say("  " + (i + 1) + ". " + logins.get(i));
            String pick = ask("Number", "1");
            try {
                return logins.get(Integer.parseInt(pick.strip()) - 1);
            } catch (RuntimeException e) {
                say("Not one of the numbers above.");
                return null;
            }
        }

        void installDesk() throws IOException, InterruptedException {
            Path target = APP.resolve("desk");
            Path fresh = APP.resolve("desk.new");
            deleteTree(fresh);
            if (desk != null && Files.isDirectory(desk)) {
                copyTree(desk, fresh);
            } else {
                Path zip = desk != null ? desk : download(RELEASES.formatted("elf-desk", "elf-desk.zip"), APP.resolve("elf-desk.zip"));
                unzip(zip, fresh);
            }
            if (!Files.isRegularFile(fresh.resolve("quarkus-run.jar"))) {
                throw new IllegalStateException("That desk build has no quarkus-run.jar at its top");
            }
            deleteTree(APP.resolve("desk.old"));
            if (Files.exists(target)) Files.move(target, APP.resolve("desk.old"));
            Files.move(fresh, target);
        }

        void installRunner() throws IOException, InterruptedException {
            Path jar = APP.resolve("elf-runner.jar");
            if (runner != null) Files.copy(runner, jar, StandardCopyOption.REPLACE_EXISTING);
            else download(RELEASES.formatted("elf-runner", "elf-runner.jar"), jar);
        }
    }

    // ---- doctor ----

    @Command(name = "doctor", description = "Check that everything the desk and its runner need is installed, logged in and running.")
    static class Doctor implements Callable<Integer> {

        int problems;

        @Override
        public Integer call() throws Exception {
            return report(true) ? 0 : 1;
        }

        /** Prints each check. With {@code installed}, also checks the services and the desk. False if anything required is missing. */
        boolean report(boolean installed) throws IOException {
            problems = 0;
            if (!MAC && !LINUX) fail("This system", "House Elves runs on Linux and macOS.");

            tool("git", List.of("git", "--version"), MAC ? "Install the Xcode command line tools: xcode-select --install"
                    : "Install git with your package manager (dnf install git, apt install git).");
            if (tool("gh", List.of("gh", "--version"), MAC ? "brew install gh" : "See https://github.com/cli/cli#installation")) {
                ghAccounts();
            }
            tool("claude", List.of("claude", "--version"), "See https://docs.claude.com/en/docs/claude-code/setup");
            if (LINUX) {
                if (tool("bwrap", List.of("bwrap", "--version"), "Install bubblewrap: dnf install bubblewrap, apt install bubblewrap.")) {
                    Result r = run(List.of("bwrap", "--ro-bind", "/", "/", "--unshare-pid", "true"));
                    if (r.ok()) ok("The agent sandbox (bubblewrap) works");
                    else fail("The agent sandbox (bubblewrap) can't start: " + firstLine(r.out),
                            "On Ubuntu 24.04 and later: sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0 "
                                    + "(and put it in /etc/sysctl.d to keep it)");
                }
            } else if (MAC) {
                if (Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) ok("The agent sandbox (sandbox-exec) is there");
                else fail("sandbox-exec is missing", "It comes with macOS; this macOS is too unusual for the sandbox.");
            }
            Result containers = run(List.of("podman", "--version"));
            if (!containers.ok()) containers = run(List.of("docker", "--version"));
            if (containers.ok()) ok("Containers: " + firstLine(containers.out));
            else warn("No Podman or Docker", "Only needed when the agent runs tests that use Dev Services or Testcontainers.");

            if (installed) {
                Map<String, String> runner = readProps(CONFIG.resolve("runner.config"));
                if (runner.getOrDefault("CLAUDE_CODE_OAUTH_TOKEN", "").isBlank()) {
                    fail("The runner has no Claude token", "Run house-elves install (it asks for one from claude setup-token).");
                } else {
                    ok("The runner has a Claude token");
                }
                for (Service s : Service.values()) {
                    if (!s.installed()) fail(s.unit + " isn't installed", "Run house-elves install.");
                    else if (s.running()) ok(s.unit + " is running");
                    else fail(s.unit + " isn't running", "Run house-elves start, and house-elves logs " + (s == Service.DESK ? "desk" : "runner") + " if it stops again.");
                }
                if (Service.DESK.running()) {
                    if (deskReady()) ok("The desk answers on " + deskUrl());
                    else fail("The desk doesn't answer on port " + PORT, "See house-elves logs desk.");
                }
            }
            return problems == 0;
        }

        boolean tool(String name, List<String> version, String fix) {
            Result r = run(version);
            if (r.ok()) {
                ok(name + ": " + firstLine(r.out));
                return true;
            }
            fail(name + " isn't installed (or isn't on PATH)", fix);
            return false;
        }

        void ghAccounts() throws IOException {
            Result r = run(List.of("gh", "auth", "status", "--json", "hosts"));
            List<JsonNode> accounts = new ArrayList<>();
            if (r.ok()) JSON.readTree(r.out).path("hosts").path("github.com").forEach(accounts::add);
            if (accounts.isEmpty()) {
                fail("gh isn't logged in to GitHub", "Run: gh auth login");
                return;
            }
            String user = readProps(CONFIG.resolve("desk.properties")).get("elfdesk.github-user");
            for (JsonNode a : accounts) {
                String login = a.path("login").asText();
                if (user != null && !user.isBlank() && !login.equals(user)) continue;
                List<String> scopes = List.of(a.path("scopes").asText("").split(",\\s*"));
                if (!a.path("state").asText().equals("success")) {
                    fail("gh's login for " + login + " doesn't work any more", "Run: gh auth login -u " + login);
                } else if (!scopes.contains("repo") || !scopes.contains("read:org")) {
                    fail("gh's login for " + login + " lacks the repo and read:org scopes",
                            "Run: gh auth refresh -u " + login + " -s repo,read:org");
                } else {
                    ok("gh is logged in as " + login + (user != null && login.equals(user) ? " (the desk's account)" : ""));
                }
            }
            if (user != null && !user.isBlank() && accounts.stream().noneMatch(a -> a.path("login").asText().equals(user))) {
                fail("gh isn't logged in as " + user + ", the desk's account", "Run: gh auth login, and pick " + user);
            }
        }

        void ok(String what) {
            say("  ✓ " + what);
        }

        void warn(String what, String note) {
            say("  ! " + what + "\n      " + note);
        }

        void fail(String what, String fix) {
            problems++;
            say("  ✗ " + what + "\n      " + fix);
        }
    }

    // ---- open, start, stop, status, logs, uninstall ----

    @Command(name = "sign-out", description = "Sign every browser out of the desk (house-elves open signs you in again).")
    static class SignOut implements Callable<Integer> {
        @Override
        public Integer call() throws IOException {
            // The desk keeps its sessions in this file and reads it again when it changes: without it, none are valid.
            Files.deleteIfExists(DATA.resolve("sessions"));
            say("Signed every browser out. Run house-elves open to sign in again.");
            return 0;
        }
    }

    @Command(name = "open", description = "Open the desk in your browser, signed in.")
    static class Open implements Callable<Integer> {

        @Option(names = "--print", description = "Print the link instead of opening it (it works once).")
        boolean print;

        @Override
        public Integer call() throws Exception {
            Path codeFile = DATA.resolve("open-code");
            if (!Files.isRegularFile(codeFile)) {
                say("The desk hasn't started yet. Run house-elves start (or house-elves install).");
                return 1;
            }
            String url = deskUrl() + "/open?code=" + Files.readString(codeFile).strip();
            if (print) {
                say(url);
                return 0;
            }
            Result r = run(List.of(MAC ? "open" : "xdg-open", url));
            if (!r.ok()) {
                say("Couldn't open a browser. Open this link (it works once):\n" + url);
            }
            return 0;
        }
    }

    @Command(name = "remote", description = "Use the desk from another machine: starts it if needed, then prints the ssh command to run there and a one-time link.")
    static class Remote implements Callable<Integer> {

        @Option(names = "--host", description = "This machine's name or address as the other machine reaches it (default: its host name).")
        String host;

        @Override
        public Integer call() throws Exception {
            if (!deskReady()) {
                say("Starting the desk…");
                for (Service s : Service.values()) s.start();
                if (!waitForDesk(Duration.ofSeconds(90))) {
                    say("The desk didn't come up: house-elves logs desk");
                    return 1;
                }
            }
            if (LINUX && !run(List.of("systemctl", "is-active", "--quiet", "sshd")).ok()) {
                say("SSH isn't running on this machine, so the other one can't connect: sudo systemctl enable --now sshd\n");
            }
            String me = System.getProperty("user.name") + "@"
                    + (host != null ? host : java.net.InetAddress.getLocalHost().getHostName());
            // The desk only listens on 127.0.0.1: the tunnel brings the other machine's port to it, and its browser
            // sends elf-desk.localhost to itself, so the desk's own name and cookie work there unchanged.
            say("On the other machine, run this and leave it open:\n\n  ssh -N -L " + PORT + ":localhost:" + PORT + " " + me
                    + "\n\nThen open this link there in Chrome, Firefox or Edge (it works once):\n\n  " + deskUrl()
                    + "/open?code=" + Files.readString(DATA.resolve("open-code")).strip()
                    + "\n\nAfter that, " + deskUrl() + " opens the desk there for 30 days, while the ssh command runs.");
            return 0;
        }
    }

    @Command(name = "start", description = "Start the desk and the runner.")
    static class Start implements Callable<Integer> {
        @Override
        public Integer call() {
            for (Service s : Service.values()) s.start();
            say(waitForDesk(Duration.ofSeconds(90)) ? "Elf Desk is up: house-elves open" : "The desk didn't come up: house-elves logs desk");
            return 0;
        }
    }

    @Command(name = "stop", description = "Stop the runner and the desk (an agent at work is stopped too).")
    static class Stop implements Callable<Integer> {
        @Override
        public Integer call() {
            Service.RUNNER.stop();
            Service.DESK.stop();
            return 0;
        }
    }

    @Command(name = "restart", description = "Restart the desk and the runner.")
    static class Restart implements Callable<Integer> {
        @Override
        public Integer call() {
            for (Service s : Service.values()) s.restart();
            return 0;
        }
    }

    @Command(name = "status", description = "Is the desk running, and is the runner connected?")
    static class Status implements Callable<Integer> {
        @Override
        public Integer call() {
            for (Service s : Service.values()) {
                say(s.unit + ": " + (!s.installed() ? "not installed" : s.running() ? "running" : "stopped"));
            }
            if (Service.DESK.running()) say("desk: " + (deskReady() ? "answering on " + deskUrl() : "not answering yet"));
            return 0;
        }
    }

    @Command(name = "logs", description = "Show the latest log lines of the desk or the runner.")
    static class Logs implements Callable<Integer> {
        @CommandLine.Parameters(description = "desk or runner", defaultValue = "desk")
        String which;

        @Option(names = { "-n", "--lines" }, defaultValue = "50")
        int lines;

        @Override
        public Integer call() throws Exception {
            Service s = which.equals("runner") ? Service.RUNNER : Service.DESK;
            if (MAC) {
                if (!Files.isRegularFile(s.log())) return 0;
                List<String> all = Files.readAllLines(s.log());
                all.subList(Math.max(0, all.size() - lines), all.size()).forEach(HouseElves::say);
            } else {
                new ProcessBuilder("journalctl", "--user", "-u", s.unit, "-n", String.valueOf(lines), "--no-pager", "-o", "cat")
                        .inheritIO().start().waitFor();
            }
            return 0;
        }
    }

    @Command(name = "uninstall", description = "Stop and remove the services and programs. Your data and settings stay unless --all.")
    static class Uninstall implements Callable<Integer> {
        @Option(names = "--all", description = "Also delete the desk's data (threads, settings) and the config with its tokens.")
        boolean all;

        @Override
        public Integer call() throws IOException {
            for (Service s : Service.values()) {
                s.stop();
                if (LINUX) run(List.of("systemctl", "--user", "disable", s.unit));
                Files.deleteIfExists(s.file());
            }
            if (LINUX) run(List.of("systemctl", "--user", "daemon-reload"));
            deleteTree(APP);
            if (all) {
                deleteTree(DATA);
                deleteTree(CONFIG);
                say("Removed House Elves, its data and its config.");
            } else {
                say("Removed the services and programs. Your data is in " + DATA + " and your settings in " + CONFIG
                        + "; house-elves install brings it all back.");
            }
            return 0;
        }
    }

    // ---- helpers ----

    record Result(int code, String out) {
        boolean ok() {
            return code == 0;
        }
    }

    static Result run(List<String> cmd) {
        return run(cmd, Map.of());
    }

    static Result run(List<String> cmd, Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().putAll(env);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, out);
            }
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            return new Result(-1, e.getMessage() == null ? "" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }

    static void check(Result r, String what) {
        if (!r.ok()) throw new IllegalStateException("Couldn't " + what + ": " + firstLine(r.out));
    }

    static String ghToken(String user) {
        Result r = run(List.of("gh", "auth", "token", "-u", user));
        check(r, "get gh's token for " + user);
        return r.out.strip();
    }

    /** A JDK 25 for the services: jbang installs one if there isn't one yet. */
    static String javaFor25() {
        Result r = run(List.of("jbang", "jdk", "home", "25"));
        String home = r.ok() ? r.out.strip().lines().reduce((a, b) -> b).orElse("") : "";
        Path java = Path.of(home, "bin", "java");
        if (!Files.isExecutable(java)) {
            check(run(List.of("jbang", "jdk", "install", "25")), "install Java 25 with jbang");
            r = run(List.of("jbang", "jdk", "home", "25"));
            java = Path.of(r.out.strip().lines().reduce((a, b) -> b).orElse(""), "bin", "java");
        }
        return java.toString();
    }

    static boolean deskReady() {
        try {
            HttpResponse<Void> r = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(HttpRequest.newBuilder(
                    // 127.0.0.1, not localhost: Java tries only ::1 for localhost, and the desk listens on IPv4.
                    URI.create("http://127.0.0.1:" + PORT + "/q/health/ready")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean waitForDesk(Duration max) {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            if (deskReady()) return true;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static Path download(String url, Path to) throws IOException, InterruptedException {
        say("  downloading " + url);
        HttpResponse<InputStream> r = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("Download failed (" + r.statusCode() + "): " + url);
        Path tmp = to.resolveSibling(to.getFileName() + ".part");
        try (InputStream in = r.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
        return to;
    }

    /** A release zip holds quarkus-app/...: its contents go straight into {@code to}. */
    static void unzip(Path zip, Path to) throws IOException {
        Files.createDirectories(to);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) {
                String name = e.getName().replaceFirst("^quarkus-app/", "");
                if (name.isEmpty()) continue;
                Path out = to.resolve(name).normalize();
                if (!out.startsWith(to)) throw new IOException("Unsafe path in the zip: " + e.getName());
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (var all = Files.walk(from)) {
            for (Path p : all.toList()) {
                Path out = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(out);
                else Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var all = Files.walk(dir)) {
            for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    static Map<String, String> readProps(Path file) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return m;
        for (String line : Files.readAllLines(file)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return m;
    }

    /** Readable only by you: these files hold tokens. */
    static void writeProps(Path file, Map<String, String> values, String comment) throws IOException {
        StringBuilder sb = new StringBuilder("# ").append(comment).append('\n');
        values.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(tmp, sb);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String random() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String uid() {
        return run(List.of("id", "-u")).out.strip();
    }

    static String ask(String question, String dflt) {
        Console c = System.console();
        if (c == null) return dflt;
        String a = c.readLine("%s [%s]: ", question, dflt);
        return a == null || a.isBlank() ? dflt : a.strip();
    }

    static String secret(String question) {
        Console c = System.console();
        if (c == null) return null;
        char[] a = c.readPassword("%s: ", question);
        return a == null ? null : new String(a);
    }

    static String firstLine(String s) {
        return s == null ? "" : s.strip().lines().findFirst().orElse("");
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String systemdQuote(String s) {
        return s.matches("[A-Za-z0-9_./:=+-]+") ? s : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static void say(String s) {
        System.out.println(s);
    }
}
