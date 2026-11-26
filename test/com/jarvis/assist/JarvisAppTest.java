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

        JarvisApp app = new JarvisApp(dir.resolve("commands"));
        check(app.handle("remind me tomorrow at 6 pm to test commands", now).startsWith("Saved reminder"), "add command");
        check(app.handle("list reminders", now).contains("test commands"), "list command");
        check(app.handle("delete reminder 1", now).contains("Deleted reminder"), "delete command");
        check(app.handle("Carrot", now).equals("Yes?"), "assistant name");
        check(app.handle("Jarvis", now).equals("Yes?"), "wake-word name");
        check(app.handle("Carrot, today", now).contains("Nothing is scheduled"), "named command prefix");
        check(app.handle("what time is it", now).contains("10:00"), "time command");
        check(app.handle("what is today's date", now).contains("2026"), "date command");
        System.out.println("All Carrot tests passed.");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
    }
}
