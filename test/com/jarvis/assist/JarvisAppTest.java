package com.jarvis.assist;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class JarvisAppTest {
    public static void main(String[] args) throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 10, 0);
        Reminder once = CommandParser.parseReminder("remind me tomorrow at 6:30 pm to call home", now);
        check(once.date().equals(LocalDate.of(2026, 11, 27)) && once.time().getHour() == 18, "one-time parse");
        Reminder daily = CommandParser.parseReminder("daily at 8:00 am remind me to take vitamins", now);
        check(daily.daily() && daily.time().getHour() == 8 && daily.lastFired().equals(now.toLocalDate()), "daily parse");
        Reminder spoken = CommandParser.parseReminder("set a reminder for seven p.m. to test the popup", now);
        check(spoken.time().getHour() == 19 && spoken.text().equals("test the popup"), "spoken reminder parse");
        Reminder everyDay = CommandParser.parseReminder("remind me every day at 9 am to stretch", now);
        check(everyDay.daily() && everyDay.time().getHour() == 9, "natural daily parse");

        var dir = Files.createTempDirectory("jarvis-test-");
        var file = dir.resolve("reminders.tsv");
        ReminderStore first = new ReminderStore(file);
        first.add(once);
        first.add(daily);
        ReminderStore restarted = new ReminderStore(file);
        String schedule = restarted.scheduleFor(LocalDate.of(2026, 11, 27));
        check(schedule.contains("call home") && schedule.contains("take vitamins"), "restart persistence");
        check(restarted.listAll().contains("1.") && restarted.listAll().contains("2."), "numbered reminder list");
        Reminder snoozed = restarted.snooze(once, now, 10);
        check(snoozed.date().equals(now.toLocalDate()) && snoozed.time().equals(now.toLocalTime().plusMinutes(10)), "snooze");
        Reminder removed = restarted.delete(3);
        check(removed.text().equals("call home") && !restarted.listAll().contains("3."), "delete reminder");
        var firedFile = dir.resolve("fired-reminders.tsv");
        ReminderStore firedStore = new ReminderStore(firedFile);
        firedStore.add(CommandParser.parseReminder("remind me at 10:01 am to test persistence", now));
        check(firedStore.dueAt(now.plusMinutes(2)).size() == 1, "due reminder fires");
        check(firedStore.snapshot().size() == 1 && firedStore.snapshot().get(0).lastFired() != null, "fired reminder retained");
        check(new ReminderStore(firedFile).snapshot().size() == 1, "fired reminder restart persistence");

        JarvisApp app = new JarvisApp(dir.resolve("commands"));
        check(app.handle("remind me tomorrow at 6 pm to test commands", now).startsWith("Saved reminder"), "add command");
        check(app.handle("list reminders", now).contains("test commands"), "list command");
        check(app.handle("delete reminder 1", now).contains("Deleted reminder"), "delete command");
        check(app.handle("Carrot", now).equals("Yes?"), "assistant name");
        check(app.handle("Jarvis", now).equals("Yes?"), "wake-word name");
        check(app.handle("Carrot, today", now).contains("Nothing is scheduled"), "named command prefix");
        check(app.handle("what time is it", now).contains("10:00"), "time command");
        check(app.handle("What's the time?", now).contains("10:00"), "punctuated voice synonym");
        check(app.handle("what is today's date", now).contains("2026"), "date command");
        check(app.handle("remember that my project is Carrot", now).contains("I'll remember"), "remember command");
        check(app.handle("what do you remember about me", now).contains("Carrot"), "memory retrieval");
        check(app.handle("forget memory 1", now).contains("Forgot"), "forget command");
        check(app.handle("add task test the local assistant", now).contains("Task added"), "add task");
        check(app.handle("list tasks", now).contains("test the local assistant"), "list tasks");
        check(app.handle("complete task 1", now).contains("Completed"), "complete task");
        check(app.handle("delete task 1", now).contains("Deleted"), "delete task");
        check(OllamaClient.jsonString("{\"response\":\"hello\\nworld\"}", "response").equals("hello\nworld"), "Ollama JSON parser");
        SystemTelemetry.Snapshot telemetry = new SystemTelemetry().read();
        check(telemetry.cpuPercent() >= 0 && telemetry.memoryPercent() >= 0 && telemetry.processCount() > 0, "real telemetry");
        check(app.handle("go to sleep", now).contains("Going to sleep"), "sleep command");
        check(app.handle("wake up carrot", now).contains("awake"), "wake command");
        System.out.println("All Carrot tests passed.");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
    }
}
