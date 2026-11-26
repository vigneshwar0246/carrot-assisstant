package com.jarvis.assist;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JarvisApp {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
    private static FileChannel lockChannel;
    private static FileLock appLock;
    private final Path dataDir;
    private final ReminderStore store;
    private final MemoryStore memoryStore;
    private final TaskStore taskStore;
    private final OllamaClient ollama = new OllamaClient();
    private final SystemTelemetry telemetry = new SystemTelemetry();
    private final AuditLog audit;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "jarvis-reminders");
        thread.setDaemon(true);
        return thread;
    });
    private TrayIcon trayIcon;
    private JFrame frame;
    private JTextArea output;
    private volatile Reminder lastNotified;
    private volatile long voiceAwakeUntil;
    private volatile long ignoreVoiceUntil;
    private volatile String voiceStatus = "STARTING";
    private volatile String clapStatus = "STARTING";
    private volatile String whisperStatus = "STARTING";
    private volatile boolean whisperReady;
    private volatile boolean conversationActive;
    private volatile double audioLevel;
    private volatile String liveTranscript = "";
    private volatile WakeMode wakeMode = WakeMode.CARROT;
    private Process voiceProcess;
    private Process whisperProcess;
    private BufferedWriter whisperInput;
    private volatile Path whisperPendingFile;
    private volatile boolean whisperPendingWakeProbe;
    private HttpServer webServer;
    private int webPort;
    private long lastDashboardOpen;
    private boolean dashboardAutoOpened;
    private long lastVoiceEventAt;
    private String lastVoiceEvent = "";
    private long lastClap;
    private final Deque<Conversation> conversations = new ArrayDeque<>();
    private final Deque<Long> apiRequests = new ArrayDeque<>();
    private final String sessionToken = UUID.randomUUID().toString();

    JarvisApp(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        store = new ReminderStore(dataDir.resolve("reminders.tsv"));
        memoryStore = new MemoryStore(dataDir.resolve("memories.tsv"));
        taskStore = new TaskStore(dataDir.resolve("tasks.tsv"));
        audit = new AuditLog(dataDir.resolve("audit.jsonl"));
    }

    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            JarvisApp app = new JarvisApp(parsed.dataDir());
            if (parsed.command() != null) {
                System.out.println(app.handle(parsed.command(), LocalDateTime.now()));
                return;
            }
            if (!SystemTray.isSupported()) {
                JOptionPane.showMessageDialog(null, "Carrot needs the Windows system tray.",
                        "Carrot", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!acquireLock(parsed.dataDir())) {
                JOptionPane.showMessageDialog(null, "Carrot is already running in the system tray.",
                        "Carrot", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            SwingUtilities.invokeLater(app::startUi);
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, e.getMessage(), "Carrot", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static boolean acquireLock(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        lockChannel = FileChannel.open(dataDir.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        appLock = lockChannel.tryLock();
        return appLock != null;
    }

    private void startUi() {
        try {
            buildFrame();
            startWebServer();
            recordConversation("assistant", "System online. Say Carrot to wake me.");
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open Carrot");
            open.addActionListener(e -> openDashboard(true));
            MenuItem today = new MenuItem("Today's schedule");
            today.addActionListener(e -> {
                String response = processCommand("today");
                append("You", "today");
                append("Carrot", response);
                openDashboard(true);
            });
            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener(e -> shutdown());
            menu.add(open);
            menu.add(today);
            menu.addSeparator();
            menu.add(exit);

            trayIcon = new TrayIcon(makeIcon(), "Carrot", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> openDashboard(true));
            SystemTray.getSystemTray().add(trayIcon);
            scheduler.scheduleAtFixedRate(this::checkDueReminders, 2, 15, TimeUnit.SECONDS);
            startWhisperService();
            startVoiceListener();
            startClapListener();
            ollama.checkAsync();
            trayIcon.displayMessage("Carrot", "Online. Say Carrot or click the tray icon. Clap wake is optional in Settings.",
                    TrayIcon.MessageType.INFO);
        } catch (AWTException | IOException e) {
            throw new IllegalStateException("Could not start Carrot.", e);
        }
    }

    private void buildFrame() {
        frame = new JFrame("Carrot");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { frame.setVisible(false); }
        });
        output = new JTextArea(16, 58);
        output.setEditable(false);
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        output.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        output.setText("Carrot: Ready. Type help to see examples. Closing this window keeps Carrot in the tray.\n\n");

        JTextField input = new JTextField();
        input.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        JButton send = new JButton("Send");
        Runnable submit = () -> {
            String command = input.getText().trim();
            if (command.isEmpty()) return;
            append("You", command);
            append("Carrot", handle(command, LocalDateTime.now()));
            input.setText("");
            input.requestFocusInWindow();
        };
        input.addActionListener(e -> submit.run());
        send.addActionListener(e -> submit.run());

        JPanel entry = new JPanel(new BorderLayout(8, 0));
        entry.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        entry.add(input, BorderLayout.CENTER);
        entry.add(send, BorderLayout.EAST);
        frame.add(new JScrollPane(output), BorderLayout.CENTER);
        frame.add(entry, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private void showFrame() {
        frame.setVisible(true);
        frame.setState(Frame.NORMAL);
        frame.toFront();
    }

    private synchronized void openDashboard(boolean force) {
        if (webPort == 0 || !Desktop.isDesktopSupported()) return;
        long now = System.currentTimeMillis();
        if (!force && dashboardAutoOpened) return;
        if (!force && now - lastDashboardOpen < 10_000) return;
        lastDashboardOpen = now;
        if (!force) dashboardAutoOpened = true;
        ollama.warmAsync();
        try {
            Desktop.getDesktop().browse(URI.create("http://127.0.0.1:" + webPort + "/"));
        } catch (IOException e) {
            showFrame();
        }
    }

    private synchronized void enterDormantMode() {
        conversationActive = false;
        voiceAwakeUntil = 0;
        liveTranscript = "";
        dashboardAutoOpened = false;
        lastDashboardOpen = 0;
        if (!voiceStatus.equals("SHUTDOWN")) voiceStatus = "ARMED";
        ollama.sleepAsync();
        audit.write("control", "dashboard-closed-dormant", true);
    }

    private void append(String speaker, String text) {
        output.append(speaker + ": " + text + "\n\n");
        output.setCaretPosition(output.getDocument().getLength());
    }

    String handle(String command, LocalDateTime now) {
        String normalized = command.trim().replaceAll("\\s+", " ").replaceAll("[.!?]+$", "");
        if (normalized.matches("(?i)(jarvis|carrot)[,.]?")) return "Yes?";
        normalized = normalized.replaceFirst("(?i)^(jarvis|carrot)\\b\\s*,?\\s*", "");
        try {
            if (normalized.matches("(?i)(wake up|wake up carrot|resume listening)")) {
                conversationActive = true; voiceStatus = "LISTENING"; ollama.warmAsync(); audit.write("control", "wake", true); return "I'm awake and listening.";
            }
            if (voiceStatus.equals("SHUTDOWN")) return "Carrot is shut down. Use the Wake control in Settings.";
            if (normalized.matches("(?i)(go to sleep|sleep|stop listening)")) {
                conversationActive = false; voiceAwakeUntil = 0; voiceStatus = "ARMED"; audit.write("control", "sleep", true); return "Going to sleep. Say Carrot when you need me.";
            }
            if (normalized.matches("(?i)(shutdown carrot|shut down carrot|emergency stop)")) {
                conversationActive = false; voiceAwakeUntil = 0; voiceStatus = "SHUTDOWN"; audit.write("control", "shutdown", true); return "Voice monitoring is shut down. Use Settings to wake me.";
            }
            if (normalized.matches("(?i)(help|what can you do|capabilities)")) return help();
            Matcher remember = Pattern.compile("(?i)remember that (.+)").matcher(normalized);
            if (remember.matches()) { MemoryStore.Memory saved=memoryStore.remember(remember.group(1)); audit.write("memory", "remember", true); return "I'll remember that: " + saved.text(); }
            if (normalized.matches("(?i)(what do you remember(?: about me)?|list memories|memories)")) return listMemories();
            Matcher forget = Pattern.compile("(?i)forget memory (\\d+)").matcher(normalized);
            if (forget.matches()) { MemoryStore.Memory removed=memoryStore.forget(Integer.parseInt(forget.group(1))); audit.write("memory", "forget", true); return "Forgot: " + removed.text(); }
            if (normalized.matches("(?i)clear (?:this )?conversation")) { synchronized(this){conversations.clear();} audit.write("conversation", "clear", true); return "Conversation context cleared. Saved memories were not changed."; }
            Matcher addTask = Pattern.compile("(?i)(?:add task|create task|todo) (.+)").matcher(normalized);
            if (addTask.matches()) { TaskStore.Task task=taskStore.add(addTask.group(1)); audit.write("task", "add", true); return "Task added: " + task.text(); }
            if (normalized.matches("(?i)(list tasks|tasks|show tasks|show my tasks)")) return listTasks();
            Matcher completeTask=Pattern.compile("(?i)(?:complete|finish) task (\\d+)").matcher(normalized);
            if(completeTask.matches()){TaskStore.Task task=taskStore.complete(Integer.parseInt(completeTask.group(1)));audit.write("task","complete",true);return "Completed task: "+task.text();}
            Matcher deleteTask=Pattern.compile("(?i)delete task (\\d+)").matcher(normalized);
            if(deleteTask.matches()){TaskStore.Task task=taskStore.delete(Integer.parseInt(deleteTask.group(1)));audit.write("task","delete",true);return "Deleted task: "+task.text();}
            if (normalized.matches("(?i)(what time is it|what's the time|tell me the time|time|current time)")) {
                return "It is " + DISPLAY_TIME.format(now.toLocalTime()) + ".";
            }
            if (normalized.matches("(?i)(what(?:'s| is) (?:today's|the) date|today's date|date)")) {
                return "Today is " + now.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) + ".";
            }
            if (normalized.matches("(?i)(today|today's schedule|today schedule|schedule today|schedule|show my schedule)")) {
                return store.scheduleFor(now.toLocalDate());
            }
            if (normalized.matches("(?i)(list reminders|reminders|show reminders|show my reminders)")) {
                return store.listAll();
            }
            Matcher delete = Pattern.compile("(?i)delete reminder (\\d+)").matcher(normalized);
            if (delete.matches()) {
                Reminder removed = store.delete(Integer.parseInt(delete.group(1)));
                return "Deleted reminder: " + removed.text();
            }
            Matcher snooze = Pattern.compile("(?i)snooze(?: for)? (\\d+) minutes?").matcher(normalized);
            if (snooze.matches()) {
                if (lastNotified == null) return "There isn't a recent reminder to snooze.";
                int minutes = Integer.parseInt(snooze.group(1));
                Reminder snoozed = store.snooze(lastNotified, now, minutes);
                return "Snoozed for " + minutes + " minute" + (minutes == 1 ? "" : "s")
                        + ", until " + DISPLAY_TIME.format(snoozed.time()) + ".";
            }
            if (normalized.toLowerCase(Locale.ROOT).startsWith("open ")) {
                return openApprovedApp(normalized.substring(5).trim());
            }
            Reminder reminder = CommandParser.parseReminder(normalized, now);
            if (reminder != null) {
                store.add(reminder);
                audit.write("reminder", "created", true);
                return confirmation(reminder);
            }
            voiceStatus = "THINKING";
            String answer = ollama.answer(aiPrompt(normalized));
            audit.write("ai", normalized, !answer.contains("unavailable") && !answer.contains("did not respond"));
            return answer;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (IOException e) {
            return "I couldn't save that reminder: " + e.getMessage();
        }
    }

    private String listMemories() {
        List<MemoryStore.Memory> items=memoryStore.snapshot(); if(items.isEmpty())return "I don't have any approved memories yet.";
        StringBuilder out=new StringBuilder("What I remember:\n");for(int i=0;i<items.size();i++)out.append(i+1).append(". ").append(items.get(i).text()).append('\n');return out.toString().stripTrailing();
    }

    private String listTasks() {
        List<TaskStore.Task> items=taskStore.snapshot();if(items.isEmpty())return "You don't have any tasks.";
        StringBuilder out=new StringBuilder("Tasks:\n");for(int i=0;i<items.size();i++)out.append(i+1).append(". ").append(items.get(i).done()?"[done] ":"[open] ").append(items.get(i).text()).append('\n');return out.toString().stripTrailing();
    }

    private synchronized String aiPrompt(String request) {
        StringBuilder context=new StringBuilder();
        conversations.stream().skip(Math.max(0,conversations.size()-8)).forEach(c->context.append(c.role()).append(": ").append(c.text()).append('\n'));
        return "You are Carrot, a fast private Windows desktop assistant. Answer concisely, usually in 1-3 sentences. "
                + "Do not claim to perform computer actions; registered commands handle actions separately. If unsure, say so.\n"
                + "Approved user memories:\n"+memoryStore.promptContext()+"\nRecent conversation:\n"+context+"\nUser: "+request+"\nCarrot:";
    }

    private String confirmation(Reminder reminder) {
        if (reminder.daily()) {
            return "Saved daily reminder for " + DISPLAY_TIME.format(reminder.time()) + ": " + reminder.text();
        }
        return "Saved reminder for " + reminder.date() + " at " + DISPLAY_TIME.format(reminder.time()) + ": " + reminder.text();
    }

    private String help() {
        return "Try:\n"
                + "• remind me at 6:30 pm to call home\n"
                + "• remind me tomorrow at 9:00 am to send the report\n"
                + "• remind me on 2026-11-30 at 14:00 to join the meeting\n"
                + "• daily at 8:00 am remind me to take vitamins\n"
                + "• today\n"
                + "• list reminders\n"
                + "• delete reminder 2\n"
                + "• snooze 10 minutes (after a notification)\n"
                + "• what time is it\n"
                + "• what's today's date\n"
                + "• remember that I prefer concise answers\n"
                + "• what do you remember about me / forget memory 1\n"
                + "• add task prepare the report / list tasks / complete task 1\n"
                + "• ask any general question (answered by local Ollama)\n"
                + "• go to sleep / shutdown carrot\n"
                + "• open PowerPoint, Word, Excel, Notepad, Calculator, File Explorer, or Chrome";
    }

    private String openApprovedApp(String requested) {
        String key = requested.toLowerCase(Locale.ROOT).replace("microsoft ", "").replace("power point", "powerpoint").replace("note pad", "notepad").replace("file explore", "file explorer").trim();
        String executable;
        String display;
        switch (key) {
            case "powerpoint" -> { executable = "POWERPNT.EXE"; display = "PowerPoint"; }
            case "word" -> { executable = "WINWORD.EXE"; display = "Word"; }
            case "excel" -> { executable = "EXCEL.EXE"; display = "Excel"; }
            case "notepad" -> { return startApproved("notepad.exe", "Notepad"); }
            case "calculator", "calc" -> { return startApproved("calc.exe", "Calculator"); }
            case "file explorer", "explorer" -> { return startApproved("explorer.exe", "File Explorer"); }
            case "chrome", "google chrome" -> {
                return startFirstFound(List.of(
                        Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Google", "Chrome", "Application", "chrome.exe"),
                        Path.of(System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)"), "Google", "Chrome", "Application", "chrome.exe")), "Chrome");
            }
            default -> { return "That app isn't approved. Try PowerPoint, Word, Excel, Notepad, Calculator, File Explorer, or Chrome."; }
        }
        return startFirstFound(officeCandidates(executable), display);
    }

    private List<Path> officeCandidates(String executable) {
        return List.of(
                Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Microsoft Office", "root", "Office16", executable),
                Path.of(System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)"), "Microsoft Office", "root", "Office16", executable),
                Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Microsoft Office", "Office16", executable),
                Path.of(System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)"), "Microsoft Office", "Office16", executable));
    }

    private String startFirstFound(List<Path> candidates, String display) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return startApproved(candidate.toString(), display);
            }
        }
        return display + " is approved, but I couldn't find it on this PC.";
    }

    private String startApproved(String executable, String display) {
        try {
            new ProcessBuilder(executable).start();
            return "Opening " + display + ".";
        } catch (IOException e) {
            return "I couldn't open " + display + ": " + e.getMessage();
        }
    }

    private String processCommand(String command) {
        recordConversation("user", command);
        String response = handle(command, LocalDateTime.now());
        recordConversation("assistant", response);
        return response;
    }

    private synchronized void recordConversation(String role, String text) {
        conversations.addLast(new Conversation(role, text, LocalTime.now().withNano(0).toString()));
        while (conversations.size() > 40) conversations.removeFirst();
    }

    private void startWebServer() throws IOException {
        webServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 17865), 0);
        webPort = webServer.getAddress().getPort();
        webServer.createContext("/", this::serveHttp);
        webServer.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "carrot-http");
            thread.setDaemon(true);
            return thread;
        }));
        webServer.start();
    }

    private void serveHttp(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/health")) { send(exchange,200,"application/json; charset=utf-8","{\"status\":\"ok\"}"); return; }
            if (path.equals("/api/readiness")) { send(exchange,200,"application/json; charset=utf-8","{\"ready\":true,\"ollama\":"+ollama.available()+",\"whisper\":"+whisperReady+"}"); return; }
            if (path.startsWith("/api/") && !authorized(exchange)) { send(exchange,401,"application/json; charset=utf-8","{\"error\":\"Unauthorized local session. Reload the dashboard.\"}"); return; }
            if (exchange.getRequestMethod().equals("POST") && !allowApiRequest()) { send(exchange,429,"application/json; charset=utf-8","{\"error\":\"Too many requests. Try again shortly.\"}"); return; }
            if (path.equals("/api/status") && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, "application/json; charset=utf-8", statusJson());
                return;
            }
            if(path.equals("/api/audio")&&exchange.getRequestMethod().equals("GET")){
                double level=voiceStatus.equals("SPEAKING")?0:audioLevel;
                send(exchange,200,"application/json; charset=utf-8","{\"level\":"+String.format(Locale.ROOT,"%.3f",level)+",\"state\":\""+json(voiceStatus)+"\",\"partial\":\""+json(liveTranscript)+"\"}");return;
            }
            if (path.equals("/api/command") && exchange.getRequestMethod().equals("POST")) {
                String command = readBody(exchange);
                if (command.isBlank()) {
                    send(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"Command is empty.\"}");
                    return;
                }
                voiceStatus = "THINKING";
                String response = processCommand(command);
                speak(response);
                send(exchange, 200, "application/json; charset=utf-8", "{\"response\":\"" + json(response) + "\"}");
                return;
            }
            if (path.equals("/api/wake-mode") && exchange.getRequestMethod().equals("POST")) {
                try {
                    wakeMode = WakeMode.parse(readBody(exchange));
                    send(exchange, 200, "application/json; charset=utf-8", "{\"wakeMode\":\"" + wakeMode.api + "\"}");
                } catch (IllegalArgumentException e) {
                    send(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"Invalid wake mode.\"}");
                }
                return;
            }
            if(path.equals("/api/control")&&exchange.getRequestMethod().equals("POST")){
                String action=readBody(exchange);String command=switch(action.toLowerCase(Locale.ROOT)){case "wake"->"wake up carrot";case "sleep"->"go to sleep";case "shutdown","emergency-stop"->"shutdown carrot";default->throw new IllegalArgumentException("Invalid control action");};
                String response=processCommand(command);send(exchange,200,"application/json; charset=utf-8","{\"response\":\""+json(response)+"\"}");return;
            }
            if(path.equals("/api/dormant")&&exchange.getRequestMethod().equals("POST")){
                enterDormantMode();send(exchange,200,"application/json; charset=utf-8","{\"dormant\":true}");return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
                return;
            }
            serveStatic(exchange, path);
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=utf-8", "{\"error\":\"" + json(e.getMessage()) + "\"}");
        }
    }

    private boolean authorized(HttpExchange exchange){String cookie=exchange.getRequestHeaders().getFirst("Cookie");return cookie!=null&&Arrays.stream(cookie.split(";")).map(String::trim).anyMatch(v->v.equals("carrot_session="+sessionToken));}
    private synchronized boolean allowApiRequest(){long now=System.currentTimeMillis();while(!apiRequests.isEmpty()&&now-apiRequests.peekFirst()>60_000)apiRequests.removeFirst();if(apiRequests.size()>=120)return false;apiRequests.addLast(now);return true;}

    private String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(4097);
        if (bytes.length > 4096) throw new IllegalArgumentException("Command is too long.");
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private void serveStatic(HttpExchange exchange, String requestPath) throws IOException {
        Path root = webRoot().toAbsolutePath().normalize();
        String relative = requestPath.equals("/") ? "index.html" : requestPath.substring(1);
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml" : "text/html; charset=utf-8";
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        if(name.equals("index.html"))exchange.getResponseHeaders().add("Set-Cookie","carrot_session="+sessionToken+"; HttpOnly; SameSite=Strict; Path=/");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private Path webRoot() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) return Path.of(appPath).getParent().resolve("app").resolve("web");
        return Path.of("web");
    }

    private void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private synchronized String statusJson() {
        StringBuilder history = new StringBuilder("[");
        for (Conversation item : conversations) {
            if (history.length() > 1) history.append(',');
            history.append("{\"role\":\"").append(item.role()).append("\",\"text\":\"")
                    .append(json(item.text())).append("\",\"time\":\"").append(item.time()).append("\"}");
        }
        history.append(']');
        List<Reminder> reminders = store.snapshot();
        StringBuilder reminderJson = new StringBuilder("[");
        for (int i = 0; i < reminders.size(); i++) {
            Reminder r = reminders.get(i);
            if (i > 0) reminderJson.append(',');
            reminderJson.append("{\"number\":").append(i + 1).append(",\"daily\":").append(r.daily())
                    .append(",\"fired\":").append(!r.daily() && r.lastFired() != null)
                    .append(",\"date\":").append(r.date() == null ? "null" : "\"" + r.date() + "\"")
                    .append(",\"time\":\"").append(r.time()).append("\",\"text\":\"")
                    .append(json(r.text())).append("\"}");
        }
        reminderJson.append(']');
        List<MemoryStore.Memory> memories=memoryStore.snapshot();
        StringBuilder memoryJson=new StringBuilder("[");
        for(int i=0;i<memories.size();i++){if(i>0)memoryJson.append(',');MemoryStore.Memory m=memories.get(i);memoryJson.append("{\"number\":").append(i+1).append(",\"text\":\"").append(json(m.text())).append("\"}");}memoryJson.append(']');
        List<TaskStore.Task> tasks=taskStore.snapshot();
        StringBuilder taskJson=new StringBuilder("[");
        for(int i=0;i<tasks.size();i++){if(i>0)taskJson.append(',');TaskStore.Task t=tasks.get(i);taskJson.append("{\"number\":").append(i+1).append(",\"done\":").append(t.done()).append(",\"text\":\"").append(json(t.text())).append("\"}");}taskJson.append(']');
        SystemTelemetry.Snapshot system=telemetry.read();
        return "{\"online\":true,\"voiceStatus\":\"" + json(voiceStatus)
                + "\",\"clapStatus\":\"" + clapStatus + "\",\"whisperStatus\":\"" + json(whisperStatus)
                + "\",\"wakeWord\":\"CARROT\",\"wakeMode\":\"" + wakeMode.api
                + "\",\"reminderCount\":" + reminders.size() + ",\"history\":" + history
                + ",\"reminders\":" + reminderJson + ",\"memories\":"+memoryJson+",\"tasks\":"+taskJson
                + ",\"provider\":{\"name\":\"Ollama\",\"model\":\""+json(ollama.model())+"\",\"available\":"+ollama.available()+"}"
                + ",\"system\":{\"cpuPercent\":"+system.cpuPercent()+",\"memoryPercent\":"+system.memoryPercent()+",\"memoryUsed\":"+system.memoryUsed()+",\"memoryTotal\":"+system.memoryTotal()+",\"diskPercent\":"+system.diskPercent()+",\"diskFree\":"+system.diskFree()+",\"uptimeSeconds\":"+system.uptimeSeconds()+",\"processCount\":"+system.processCount()+",\"networkOnline\":"+system.networkOnline()+"}}";
    }

    private static String json(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 32) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }

    private void checkDueReminders() {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Reminder reminder : store.dueAt(now)) {
                lastNotified = reminder;
                recordConversation("assistant", "Reminder: " + reminder.text());
                SwingUtilities.invokeLater(() -> showReminderPopup(reminder));
            }
        } catch (Exception e) {
            System.err.println("Reminder check failed: " + e.getMessage());
        }
    }

    private void showReminderPopup(Reminder reminder) {
        Toolkit.getDefaultToolkit().beep();
        trayIcon.displayMessage("Carrot Reminder", reminder.text(), TrayIcon.MessageType.INFO);
        JDialog dialog = new JDialog((Frame) null, "Carrot Reminder", false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setAlwaysOnTop(true);

        JLabel heading = new JLabel("REMINDER");
        heading.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        heading.setForeground(new Color(220, 92, 42));
        JTextArea message = new JTextArea(reminder.text(), 3, 34);
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
        JButton dismiss = new JButton("Dismiss ×");
        dismiss.addActionListener(e -> dialog.dispose());

        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        content.add(heading, BorderLayout.NORTH);
        content.add(message, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(dismiss);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        dialog.toFront();
    }

    private void startWhisperService() {
        Path python = whisperPython();
        Path script = whisperScript();
        if (!Files.isRegularFile(python) || !Files.isRegularFile(script)) {
            whisperStatus = "NOT INSTALLED";
            whisperReady = false;
            return;
        }
        Thread service = new Thread(() -> {
            try {
                whisperStatus = "LOADING";
                whisperProcess = new ProcessBuilder(python.toString(), "-u", script.toString())
                        .redirectErrorStream(true).start();
                whisperInput = new BufferedWriter(new OutputStreamWriter(
                        whisperProcess.getOutputStream(), StandardCharsets.UTF_8));
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        whisperProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("WHISPER_READY")) {
                            whisperReady = true;
                            whisperStatus = "READY";
                        } else if (line.startsWith("WHISPER:")) {
                            String text = decodeWhisper(line.substring(8));
                            boolean wakeProbe = whisperPendingWakeProbe;
                            whisperStatus = "READY";
                            deleteWhisperRecording();
                            handleWhisperText(text, wakeProbe);
                        } else if (line.startsWith("WHISPER_ERROR:")) {
                            String message = decodeWhisper(line.substring(14));
                            deleteWhisperRecording();
                            whisperStatus = "ERROR";
                            liveTranscript = message.isBlank() ? "Transcription failed" : message;
                            if (conversationActive) voiceStatus = "LISTENING";
                        }
                    }
                }
            } catch (IOException e) {
                whisperStatus = "UNAVAILABLE";
                System.err.println("Whisper service failed: " + e.getMessage());
            } finally {
                whisperReady = false;
                deleteWhisperRecording();
            }
        }, "carrot-whisper-service");
        service.setDaemon(true);
        service.start();
    }

    private Path whisperPython() {
        String configured = System.getenv("CARROT_WHISPER_PYTHON");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String local = System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home"));
        return Path.of(local, "Carrot", "whisper-env", "Scripts", "python.exe");
    }

    private Path whisperScript() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) return Path.of(appPath).getParent().resolve("app").resolve("whisper-service.py");
        return Path.of("scripts", "whisper-service.py").toAbsolutePath();
    }

    private static String decodeWhisper(String encoded) {
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private synchronized void submitWhisper(byte[] pcm, AudioFormat format, boolean wakeProbe) {
        if (!whisperReady || whisperInput == null || pcm.length == 0 || whisperPendingFile != null) return;
        try {
            Path tempDir = dataDir.resolve("temp");
            Files.createDirectories(tempDir);
            Path recording = Files.createTempFile(tempDir, "carrot-command-", ".wav");
            try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(pcm), format,
                    pcm.length / Math.max(1, format.getFrameSize()))) {
                AudioSystem.write(audio, AudioFileFormat.Type.WAVE, recording.toFile());
            }
            whisperPendingFile = recording;
            whisperPendingWakeProbe = wakeProbe;
            voiceStatus = "TRANSCRIBING";
            liveTranscript = "Transcribing...";
            whisperInput.write(Base64.getEncoder().encodeToString(
                    recording.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8)));
            whisperInput.newLine();
            whisperInput.flush();
        } catch (IOException e) {
            whisperStatus = "ERROR";
            deleteWhisperRecording();
            voiceStatus = conversationActive ? "LISTENING" : "ARMED";
            System.err.println("Could not submit voice command: " + e.getMessage());
        }
    }

    private void handleWhisperText(String text, boolean wakeProbe) {
        if (text == null || text.isBlank()) {
            liveTranscript = wakeProbe ? "" : "I didn't catch that. Please try again.";
            voiceStatus = wakeProbe ? "ARMED" : (conversationActive ? "LISTENING" : "ARMED");
            return;
        }
        String commandText = text;
        if (wakeProbe) {
            Matcher wake = Pattern.compile("(?i).*?\\b(?:jarvis|carrot)\\b[,.]?\\s*(.*)").matcher(text);
            if (!wake.matches()) {
                liveTranscript = "";
                voiceStatus = "ARMED";
                return;
            }
            commandText = wake.group(1).trim();
            conversationActive = true;
            voiceAwakeUntil = System.currentTimeMillis() + 60_000;
            openDashboard(false);
            if (commandText.isEmpty()) {
                recordConversation("user", text);
                recordConversation("assistant", "Yes?");
                SwingUtilities.invokeLater(() -> {
                    append("You (voice)", text);
                    append("Carrot", "Yes?");
                });
                speak("Yes?");
                return;
            }
        }
        liveTranscript = text;
        voiceAwakeUntil = System.currentTimeMillis() + 60_000;
        String spokenCommand = commandText;
        Thread worker = new Thread(() -> {
            voiceStatus = "THINKING";
            SwingUtilities.invokeLater(() -> append("You (voice)", text));
            String response = processCommand(spokenCommand);
            SwingUtilities.invokeLater(() -> append("Carrot", response));
            speak(response);
            liveTranscript = "";
        }, "carrot-whisper-command");
        worker.setDaemon(true);
        worker.start();
    }

    private synchronized void deleteWhisperRecording() {
        Path recording = whisperPendingFile;
        whisperPendingFile = null;
        whisperPendingWakeProbe = false;
        if (recording != null) {
            try { Files.deleteIfExists(recording); } catch (IOException ignored) { }
        }
    }

    private void startVoiceListener() {
        Path script = voiceScript();
        if (!Files.isRegularFile(script)) {
            voiceStatus = "UNAVAILABLE";
            return;
        }
        Thread listener = new Thread(() -> {
            try {
                voiceProcess = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString()).redirectErrorStream(true).start();
                voiceStatus = "ARMED";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        voiceProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("VOICE:")) receiveVoice(line.substring(6));
                        else if(line.startsWith("PARTIAL:")){
                            if (voiceStatus.equals("LISTENING")) {
                                try{liveTranscript=new String(Base64.getDecoder().decode(line.substring(8)),StandardCharsets.UTF_8).trim();}catch(IllegalArgumentException ignored){}
                            }
                        }else if(line.equals("DETECTED")&&conversationActive
                                && (voiceStatus.equals("ARMED") || voiceStatus.equals("LISTENING")))voiceStatus="LISTENING";
                    }
                    voiceStatus = "UNAVAILABLE";
                }
            } catch (IOException e) {
                voiceStatus = "UNAVAILABLE";
                System.err.println("Voice listener failed: " + e.getMessage());
            }
        }, "carrot-voice-listener");
        listener.setDaemon(true);
        listener.start();
    }

    private Path voiceScript() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) return Path.of(appPath).getParent().resolve("app").resolve("voice-listener.ps1");
        return Path.of("scripts", "voice-listener.ps1").toAbsolutePath();
    }

    private synchronized void receiveVoice(String encoded) {
        if (voiceStatus.equals("SHUTDOWN")) return;
        if (System.currentTimeMillis() < ignoreVoiceUntil) return;
        final String heard;
        try {
            heard = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return;
        }
        long now = System.currentTimeMillis();
        String eventKey = heard.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        if (eventKey.equals(lastVoiceEvent) && now - lastVoiceEventAt < 5_000) return;
        lastVoiceEvent = eventKey;
        lastVoiceEventAt = now;
        Matcher call = Pattern.compile("(?i).*?\\b(?:jarvis|carrot)\\b[,.]?\\s*(.*)").matcher(heard);
        String command;
        if (call.matches() && wakeMode.allowsVoice()) {
            command = call.group(1).trim();
            if (command.isEmpty() && conversationActive && now < voiceAwakeUntil) return;
            voiceAwakeUntil = now + 60_000;
            conversationActive = true;
            voiceStatus = "LISTENING";
            openDashboard(false);
            if (command.isEmpty()) {
                recordConversation("user", heard);
                recordConversation("assistant", "Yes?");
                SwingUtilities.invokeLater(() -> {
                    append("You (voice)", heard);
                    append("Carrot", "Yes?");
                });
                speak("Yes?");
                return;
            }
        } else if (conversationActive && System.currentTimeMillis() < voiceAwakeUntil) {
            // Windows Speech stays responsible for the wake word. Whisper handles the
            // follow-up command because free-form dictation is substantially more accurate.
            if (whisperReady) return;
            command = heard;
            voiceAwakeUntil = 0;
        } else {
            return;
        }
        String spokenCommand = command;
        liveTranscript=heard;
        Thread worker=new Thread(()->{
            voiceStatus = "THINKING";
            SwingUtilities.invokeLater(()->append("You (voice)",heard));
            String response=processCommand(spokenCommand);
            SwingUtilities.invokeLater(()->append("Carrot",response));
            speak(response);
            liveTranscript="";
        },"carrot-command");worker.setDaemon(true);worker.start();
    }

    private void startClapListener() {
        Thread listener = new Thread(() -> {
            AudioFormat format = new AudioFormat(16_000f, 16, 1, true, false);
            try (TargetDataLine line = AudioSystem.getTargetDataLine(format)) {
                line.open(format, 6_400);
                line.start();
                clapStatus = "LISTENING";
                byte[] buffer = new byte[640];
                double noise = 200;
                boolean burst = false;
                long burstStarted = 0;
                int burstPeak = 0;
                Deque<byte[]> preRoll = new ArrayDeque<>();
                ByteArrayOutputStream speech = null;
                int speechFrames = 0;
                int silentFrames = 0;
                int speechStartFrames = 0;
                boolean speechWakeProbe = false;
                while (!Thread.currentThread().isInterrupted()) {
                    int read = line.read(buffer, 0, buffer.length);
                    double sum = 0;
                    int peak = 0;
                    for (int i = 0; i + 1 < read; i += 2) {
                        int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                        sum += (double) sample * sample;
                        peak = Math.max(peak, Math.abs(sample));
                    }
                    double rms = Math.sqrt(sum / Math.max(1, read / 2));
                    double threshold = Math.max(1_800, noise * 4.2);
                    double signal=Math.max(0,rms-Math.max(120,noise*1.1));
                    double target=Math.min(1,signal/1_800d);
                    audioLevel=audioLevel*.65+target*.35;
                    long now = System.currentTimeMillis();
                    if (conversationActive && voiceStatus.equals("LISTENING") && now >= voiceAwakeUntil) {
                        conversationActive = false;
                        voiceStatus = "ARMED";
                        liveTranscript = "";
                    }
                    boolean canCapture = whisperReady && now >= ignoreVoiceUntil
                            && ((!conversationActive && voiceStatus.equals("ARMED"))
                            || (conversationActive && voiceStatus.equals("LISTENING")));
                    if (canCapture) {
                        byte[] frame = Arrays.copyOf(buffer, read);
                        if (speech == null) {
                            preRoll.addLast(frame);
                            while (preRoll.size() > 10) preRoll.removeFirst();
                            double startThreshold = Math.max(500, noise * 1.8);
                            speechStartFrames = rms > startThreshold ? speechStartFrames + 1 : 0;
                            if (speechStartFrames >= 4) {
                                speechWakeProbe = !conversationActive;
                                speech = new ByteArrayOutputStream(64_000);
                                for (byte[] prior : preRoll) speech.write(prior, 0, prior.length);
                                preRoll.clear();
                                speechFrames = 0;
                                silentFrames = 0;
                                liveTranscript = "Hearing you...";
                            }
                        } else {
                            speech.write(frame, 0, frame.length);
                            speechFrames++;
                            double endThreshold = Math.max(300, noise * 1.25);
                            silentFrames = rms < endThreshold ? silentFrames + 1 : 0;
                            if ((silentFrames >= 30 && speech.size() >= 16_000) || speechFrames >= 750) {
                                byte[] commandAudio = speech.toByteArray();
                                boolean wakeProbe = speechWakeProbe;
                                speech = null;
                                speechFrames = silentFrames = speechStartFrames = 0;
                                speechWakeProbe = false;
                                preRoll.clear();
                                Thread transcribe = new Thread(() -> submitWhisper(commandAudio, format, wakeProbe),
                                        "carrot-whisper-submit");
                                transcribe.setDaemon(true);
                                transcribe.start();
                            }
                        }
                    } else {
                        speech = null;
                        speechFrames = silentFrames = speechStartFrames = 0;
                        speechWakeProbe = false;
                        preRoll.clear();
                    }
                    if (rms > threshold) {
                        if (!burst) { burst = true; burstStarted = now; burstPeak = peak; }
                        burstPeak = Math.max(burstPeak, peak);
                    } else if (burst) {
                        long duration = now - burstStarted;
                        if (duration >= 20 && duration <= 240 && burstPeak >= 5_500) registerClap(now);
                        burst = false;
                    } else {
                        noise = Math.max(100, noise * .985 + rms * .015);
                    }
                }
            } catch (Exception e) {
                clapStatus = "UNAVAILABLE";
                System.err.println("Clap listener unavailable: " + e.getMessage());
            }
        }, "carrot-clap-listener");
        listener.setDaemon(true);
        listener.start();
    }

    private synchronized void registerClap(long now) {
        if (voiceStatus.equals("SHUTDOWN")) return;
        if (!wakeMode.allowsClap()) return;
        if (conversationActive && now < voiceAwakeUntil) return;
        long gap = now - lastClap;
        if (gap >= 120 && gap <= 1_000) {
            lastClap = 0;
            voiceAwakeUntil = now + 60_000;
            conversationActive = true;
            voiceStatus = "LISTENING";
            recordConversation("user", "Double clap detected");
            recordConversation("assistant", "Yes?");
            openDashboard(false);
            speak("Yes?");
        } else {
            lastClap = now;
        }
    }

    private void speak(String text) {
        boolean shutdownRequested=voiceStatus.equals("SHUTDOWN");
        voiceStatus="SPEAKING";
        ignoreVoiceUntil = System.currentTimeMillis() + Math.max(800, Math.min(12_000, text.length() * 45L));
        Thread speaker = new Thread(() -> {
            try {
                ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                        "Add-Type -AssemblyName System.Speech; $s=New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Speak($env:CARROT_SPEAK); $s.Dispose()");
                builder.environment().put("CARROT_SPEAK", text.replace('•', ' '));
                builder.start().waitFor();
            } catch (IOException e) {
                System.err.println("Speech response failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ignoreVoiceUntil=System.currentTimeMillis()+200;
                if(shutdownRequested)voiceStatus="SHUTDOWN";
                else if(conversationActive){voiceAwakeUntil=System.currentTimeMillis()+60_000;voiceStatus="LISTENING";}
                else voiceStatus="ARMED";
            }
        }, "carrot-speech");
        speaker.setDaemon(true);
        speaker.start();
    }

    private void shutdown() {
        scheduler.shutdownNow();
        if (voiceProcess != null) voiceProcess.destroy();
        whisperReady = false;
        if (whisperProcess != null) whisperProcess.destroy();
        deleteWhisperRecording();
        if (webServer != null) webServer.stop(0);
        SystemTray.getSystemTray().remove(trayIcon);
        try {
            if (appLock != null) appLock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignored) { }
        System.exit(0);
    }

    private static Image makeIcon() {
        int size = 32;
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(23, 72, 112));
        g.fillOval(1, 1, 30, 30);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString("C", 8, 24);
        g.dispose();
        return image;
    }

    record Arguments(Path dataDir, String command) {
        static Arguments parse(String[] args) {
            Path dataDir = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")), "JavaJarvisAssist");
            String command = null;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--data-dir") && i + 1 < args.length) dataDir = Path.of(args[++i]);
                else if (args[i].equals("--command") && i + 1 < args.length) command = args[++i];
                else throw new IllegalArgumentException("Unknown or incomplete argument: " + args[i]);
            }
            return new Arguments(dataDir, command);
        }
    }

    record Conversation(String role, String text, String time) { }

    enum WakeMode {
        CARROT("carrot"), CLAP("clap"), BOTH("both");
        final String api;
        WakeMode(String api) { this.api = api; }
        boolean allowsVoice() { return this != CLAP; }
        boolean allowsClap() { return this != CARROT; }
        static WakeMode parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "carrot", "jarvis" -> CARROT;
                case "clap" -> CLAP;
                case "both" -> BOTH;
                default -> throw new IllegalArgumentException("Invalid wake mode");
            };
        }
    }
}

record Reminder(UUID id, boolean daily, LocalDate date, LocalTime time, String text, LocalDate lastFired) { }

final class CommandParser {
    private static final Pattern DAILY_LEADING = Pattern.compile("(?i)daily at (.+?) remind me (?:to|about) (.+)");
    private static final Pattern DAILY = Pattern.compile("(?i)(?:remind me|set(?: a)? reminder) (?:daily|every day) at (.+?) (?:to|for|that) (.+)");
    private static final Pattern ONCE = Pattern.compile("(?i)(?:remind me|set(?: a)? reminder)(?: (today|tomorrow)| on (\\d{4}-\\d{2}-\\d{2}))? (?:at|for) (.+?) (?:to|for|that) (.+)");
    private static final DateTimeFormatter TIME_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_12_HOUR = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH);

    private CommandParser() { }

    static Reminder parseReminder(String command, LocalDateTime now) {
        Matcher leadingDaily = DAILY_LEADING.matcher(command);
        if (leadingDaily.matches()) {
            LocalTime time = parseTime(leadingDaily.group(1));
            LocalDate alreadyPassedToday = time.isAfter(now.toLocalTime()) ? null : now.toLocalDate();
            return new Reminder(UUID.randomUUID(), true, null, time, requireText(leadingDaily.group(2)), alreadyPassedToday);
        }
        Matcher daily = DAILY.matcher(command);
        if (daily.matches()) {
            LocalTime time = parseTime(daily.group(1));
            LocalDate alreadyPassedToday = time.isAfter(now.toLocalTime()) ? null : now.toLocalDate();
            return new Reminder(UUID.randomUUID(), true, null, time, requireText(daily.group(2)), alreadyPassedToday);
        }
        Matcher once = ONCE.matcher(command);
        if (!once.matches()) return null;
        LocalTime time = parseTime(once.group(3));
        LocalDate date;
        if (once.group(2) != null) {
            try { date = LocalDate.parse(once.group(2)); }
            catch (DateTimeParseException e) { throw new IllegalArgumentException("Use a date like 2026-11-30."); }
        } else if ("tomorrow".equalsIgnoreCase(once.group(1))) {
            date = now.toLocalDate().plusDays(1);
        } else {
            date = now.toLocalDate();
            if (once.group(1) == null && !time.isAfter(now.toLocalTime())) date = date.plusDays(1);
        }
        LocalDateTime scheduled = LocalDateTime.of(date, time);
        if (scheduled.isBefore(now.withSecond(0).withNano(0))) throw new IllegalArgumentException("That time is in the past.");
        return new Reminder(UUID.randomUUID(), false, date, time, requireText(once.group(4)), null);
    }

    private static String requireText(String text) {
        text = text.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Tell me what to remind you about.");
        return text;
    }

    private static LocalTime parseTime(String raw) {
        String value = raw.trim().toUpperCase(Locale.ENGLISH).replace(".", "")
                .replaceAll("\\s+", " ").replaceAll("^(\\d{1,2}) (\\d{2}) ([AP]M)$", "$1:$2 $3")
                .replaceAll("(\\d)([AP]M)$", "$1 $2");
        if (value.equals("NOON")) return LocalTime.NOON;
        if (value.equals("MIDNIGHT")) return LocalTime.MIDNIGHT;
        Map<String, String> spokenHours = Map.ofEntries(
                Map.entry("ONE", "1"), Map.entry("TWO", "2"), Map.entry("THREE", "3"),
                Map.entry("FOUR", "4"), Map.entry("FIVE", "5"), Map.entry("SIX", "6"),
                Map.entry("SEVEN", "7"), Map.entry("EIGHT", "8"), Map.entry("NINE", "9"),
                Map.entry("TEN", "10"), Map.entry("ELEVEN", "11"), Map.entry("TWELVE", "12"));
        for (Map.Entry<String, String> hour : spokenHours.entrySet()) {
            if (value.startsWith(hour.getKey() + " ")) {
                value = hour.getValue() + value.substring(hour.getKey().length());
                break;
            }
        }
        try {
            if (value.matches("\\d{1,2}:\\d{2} [AP]M")) return LocalTime.parse(value, TIME_12);
            if (value.matches("\\d{1,2} [AP]M")) return LocalTime.parse(value, TIME_12_HOUR);
            if (value.matches("\\d{1,2}")) return LocalTime.of(Integer.parseInt(value), 0);
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("H:mm"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Use a time like 6:30 pm or 18:30.");
        }
    }
}

final class ReminderStore {
    private final Path file;
    private final List<Reminder> reminders = new ArrayList<>();

    ReminderStore(Path file) throws IOException {
        this.file = file;
        load();
    }

    synchronized void add(Reminder reminder) throws IOException {
        reminders.add(reminder);
        save();
    }

    synchronized List<Reminder> snapshot() {
        return List.copyOf(reminders);
    }

    synchronized String scheduleFor(LocalDate date) {
        List<Reminder> today = reminders.stream()
                .filter(r -> r.daily() || date.equals(r.date()))
                .sorted(Comparator.comparing(Reminder::time))
                .toList();
        if (today.isEmpty()) return "Nothing is scheduled for today.";
        StringBuilder result = new StringBuilder("Today's schedule:\n");
        for (Reminder r : today) {
            result.append("• ").append(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(r.time()))
                    .append(" — ").append(r.text()).append(r.daily() ? " (daily)" : "").append('\n');
        }
        return result.toString().stripTrailing();
    }

    synchronized String listAll() {
        if (reminders.isEmpty()) return "You don't have any saved reminders.";
        StringBuilder result = new StringBuilder("Saved reminders:\n");
        for (int i = 0; i < reminders.size(); i++) {
            Reminder r = reminders.get(i);
            result.append(i + 1).append(". ");
            if (r.daily()) result.append("Daily at ");
            else result.append(r.date()).append(" at ");
            result.append(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(r.time()))
                    .append(" — ").append(r.text()).append('\n');
        }
        return result.toString().stripTrailing();
    }

    synchronized Reminder delete(int number) throws IOException {
        if (number < 1 || number > reminders.size()) {
            throw new IllegalArgumentException("Reminder " + number + " doesn't exist. Type list reminders first.");
        }
        Reminder removed = reminders.remove(number - 1);
        save();
        return removed;
    }

    synchronized Reminder snooze(Reminder reminder, LocalDateTime now, int minutes) throws IOException {
        if (minutes < 1 || minutes > 1_440) {
            throw new IllegalArgumentException("Snooze time must be between 1 and 1440 minutes.");
        }
        LocalDateTime when = now.plusMinutes(minutes).withSecond(0).withNano(0);
        Reminder snoozed = new Reminder(UUID.randomUUID(), false, when.toLocalDate(), when.toLocalTime(),
                reminder.text(), null);
        reminders.add(snoozed);
        save();
        return snoozed;
    }

    synchronized List<Reminder> dueAt(LocalDateTime now) throws IOException {
        LocalDate date = now.toLocalDate();
        List<Reminder> due = reminders.stream().filter(r -> {
                    LocalDateTime occurrence = LocalDateTime.of(r.daily() ? date : r.date(), r.time());
                    return !occurrence.isAfter(now)
                            && (r.daily() ? !date.equals(r.lastFired()) : r.lastFired() == null);
                })
                .toList();
        if (due.isEmpty()) return due;
        for (Reminder r : due) {
            int index = reminders.indexOf(r);
            reminders.set(index, new Reminder(r.id(), r.daily(), r.date(), r.time(), r.text(), date));
        }
        save();
        return due;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) continue;
            try {
                String[] fields = line.split("\\t", -1);
                reminders.add(new Reminder(UUID.fromString(fields[0]), Boolean.parseBoolean(fields[1]),
                        fields[2].isEmpty() ? null : LocalDate.parse(fields[2]), LocalTime.parse(fields[3]),
                        new String(Base64.getDecoder().decode(fields[4]), StandardCharsets.UTF_8),
                        fields.length < 6 || fields[5].isEmpty() ? null : LocalDate.parse(fields[5])));
            } catch (RuntimeException e) {
                throw new IOException("Invalid reminder data at line " + lineNumber + ".", e);
            }
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        List<String> lines = reminders.stream().map(r -> String.join("\t", r.id().toString(),
                Boolean.toString(r.daily()), r.date() == null ? "" : r.date().toString(), r.time().toString(),
                Base64.getEncoder().encodeToString(r.text().getBytes(StandardCharsets.UTF_8)),
                r.lastFired() == null ? "" : r.lastFired().toString())).toList();
        Files.write(temp, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
