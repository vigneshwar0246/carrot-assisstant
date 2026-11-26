package com.jarvis.assist;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    private final ReminderStore store;
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
    private volatile WakeMode wakeMode = WakeMode.BOTH;
    private Process voiceProcess;
    private HttpServer webServer;
    private int webPort;
    private long lastDashboardOpen;
    private long lastClap;
    private final Deque<Conversation> conversations = new ArrayDeque<>();

    JarvisApp(Path dataDir) throws IOException {
        store = new ReminderStore(dataDir.resolve("reminders.tsv"));
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
            recordConversation("assistant", "System online. Say Carrot or double clap to wake me.");
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
            startVoiceListener();
            startClapListener();
            trayIcon.displayMessage("Carrot", "Online. Say Carrot, double clap, or click the tray icon.",
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

    private void openDashboard(boolean force) {
        if (webPort == 0 || !Desktop.isDesktopSupported()) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastDashboardOpen < 10_000) return;
        lastDashboardOpen = now;
        try {
            Desktop.getDesktop().browse(URI.create("http://127.0.0.1:" + webPort + "/"));
        } catch (IOException e) {
            showFrame();
        }
    }

    private void append(String speaker, String text) {
        output.append(speaker + ": " + text + "\n\n");
        output.setCaretPosition(output.getDocument().getLength());
    }

    String handle(String command, LocalDateTime now) {
        String normalized = command.trim().replaceAll("\\s+", " ");
        if (normalized.matches("(?i)(jarvis|carrot)[,.]?")) return "Yes?";
        normalized = normalized.replaceFirst("(?i)^(jarvis|carrot)\\b\\s*,?\\s*", "");
        try {
            if (normalized.matches("(?i)(help|what can you do|capabilities)")) return help();
            if (normalized.matches("(?i)(what time is it|time|current time)")) {
                return "It is " + DISPLAY_TIME.format(now.toLocalTime()) + ".";
            }
            if (normalized.matches("(?i)(what(?:'s| is) (?:today's|the) date|today's date|date)")) {
                return "Today is " + now.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) + ".";
            }
            if (normalized.matches("(?i)(today|today's schedule|schedule today|schedule)")) {
                return store.scheduleFor(now.toLocalDate());
            }
            if (normalized.matches("(?i)(list reminders|reminders)")) {
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
                return confirmation(reminder);
            }
            return "I didn't understand that. Type help for command examples.";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (IOException e) {
            return "I couldn't save that reminder: " + e.getMessage();
        }
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
                + "• open PowerPoint, Word, Excel, Notepad, Calculator, File Explorer, or Chrome";
    }

    private String openApprovedApp(String requested) {
        String key = requested.toLowerCase(Locale.ROOT).replace("microsoft ", "").trim();
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
            if (path.equals("/api/status") && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, "application/json; charset=utf-8", statusJson());
                return;
            }
            if (path.equals("/api/command") && exchange.getRequestMethod().equals("POST")) {
                String command = readBody(exchange);
                if (command.isBlank()) {
                    send(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"Command is empty.\"}");
                    return;
                }
                voiceStatus = "PROCESSING";
                String response = processCommand(command);
                speak(response);
                voiceStatus = "LISTENING";
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
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
                return;
            }
            serveStatic(exchange, path);
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=utf-8", "{\"error\":\"" + json(e.getMessage()) + "\"}");
        }
    }

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
                    .append(",\"date\":").append(r.date() == null ? "null" : "\"" + r.date() + "\"")
                    .append(",\"time\":\"").append(r.time()).append("\",\"text\":\"")
                    .append(json(r.text())).append("\"}");
        }
        reminderJson.append(']');
        return "{\"online\":true,\"voiceStatus\":\"" + json(voiceStatus)
                + "\",\"clapStatus\":\"" + clapStatus + "\",\"wakeWord\":\"CARROT\",\"wakeMode\":\"" + wakeMode.api
                + "\",\"reminderCount\":" + reminders.size() + ",\"history\":" + history
                + ",\"reminders\":" + reminderJson + "}";
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
                SwingUtilities.invokeLater(() -> trayIcon.displayMessage("Reminder", reminder.text(), TrayIcon.MessageType.INFO));
            }
        } catch (Exception e) {
            System.err.println("Reminder check failed: " + e.getMessage());
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
                voiceStatus = "LISTENING";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        voiceProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("VOICE:")) receiveVoice(line.substring(6));
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

    private void receiveVoice(String encoded) {
        if (System.currentTimeMillis() < ignoreVoiceUntil) return;
        final String heard;
        try {
            heard = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return;
        }
        Matcher call = Pattern.compile("(?i).*?\\b(?:jarvis|carrot)\\b[,.]?\\s*(.*)").matcher(heard);
        String command;
        if (call.matches() && wakeMode.allowsVoice()) {
            voiceAwakeUntil = System.currentTimeMillis() + 15_000;
            voiceStatus = "AWAKE";
            openDashboard(false);
            command = call.group(1).trim();
            if (command.isEmpty()) {
                recordConversation("user", heard);
                recordConversation("assistant", "Yes? I'm listening.");
                SwingUtilities.invokeLater(() -> {
                    append("You (voice)", heard);
                    append("Carrot", "Yes? I'm listening.");
                });
                speak("Yes? I'm listening.");
                return;
            }
        } else if (System.currentTimeMillis() < voiceAwakeUntil) {
            command = heard;
            voiceAwakeUntil = 0;
        } else {
            return;
        }
        String spokenCommand = command;
        SwingUtilities.invokeLater(() -> {
            voiceStatus = "PROCESSING";
            append("You (voice)", heard);
            String response = processCommand(spokenCommand);
            append("Carrot", response);
            speak(response);
            voiceStatus = "LISTENING";
        });
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
                    long now = System.currentTimeMillis();
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
        if (!wakeMode.allowsClap()) return;
        long gap = now - lastClap;
        if (gap >= 120 && gap <= 1_000) {
            lastClap = 0;
            voiceAwakeUntil = now + 15_000;
            voiceStatus = "AWAKE";
            recordConversation("user", "Double clap detected");
            recordConversation("assistant", "Yes? I'm listening.");
            openDashboard(false);
            speak("Yes? I'm listening.");
        } else {
            lastClap = now;
        }
    }

    private void speak(String text) {
        ignoreVoiceUntil = System.currentTimeMillis() + Math.max(2_000, Math.min(12_000, text.length() * 55L));
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
            }
        }, "carrot-speech");
        speaker.setDaemon(true);
        speaker.start();
    }

    private void shutdown() {
        scheduler.shutdownNow();
        if (voiceProcess != null) voiceProcess.destroy();
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
    private static final Pattern DAILY = Pattern.compile("(?i)(?:daily at (.+?) remind me to|remind me daily at (.+?) to) (.+)");
    private static final Pattern ONCE = Pattern.compile("(?i)remind me (?:(today|tomorrow)|on (\\d{4}-\\d{2}-\\d{2}))? ?at (.+?) to (.+)");
    private static final DateTimeFormatter TIME_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_12_HOUR = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH);

    private CommandParser() { }

    static Reminder parseReminder(String command, LocalDateTime now) {
        Matcher daily = DAILY.matcher(command);
        if (daily.matches()) {
            String rawTime = daily.group(1) != null ? daily.group(1) : daily.group(2);
            LocalTime time = parseTime(rawTime);
            LocalDate alreadyPassedToday = time.isAfter(now.toLocalTime()) ? null : now.toLocalDate();
            return new Reminder(UUID.randomUUID(), true, null, time, requireText(daily.group(3)), alreadyPassedToday);
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
        String value = raw.trim().toUpperCase(Locale.ENGLISH).replaceAll("\\s+", " ");
        try {
            if (value.matches("\\d{1,2}:\\d{2} [AP]M")) return LocalTime.parse(value, TIME_12);
            if (value.matches("\\d{1,2} [AP]M")) return LocalTime.parse(value, TIME_12_HOUR);
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
                            && (r.daily() ? !date.equals(r.lastFired()) : true);
                })
                .toList();
        if (due.isEmpty()) return due;
        for (Reminder r : due) {
            if (r.daily()) {
                int index = reminders.indexOf(r);
                reminders.set(index, new Reminder(r.id(), true, null, r.time(), r.text(), date));
            } else {
                reminders.remove(r);
            }
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
