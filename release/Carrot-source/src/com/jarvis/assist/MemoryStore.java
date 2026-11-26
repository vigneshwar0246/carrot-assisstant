package com.jarvis.assist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class MemoryStore {
    record Memory(UUID id, String text, Instant created) { }
    private final Path file;
    private final List<Memory> items = new ArrayList<>();

    MemoryStore(Path file) throws IOException { this.file = file; load(); }

    synchronized Memory remember(String text) throws IOException {
        String clean = text.trim();
        if (clean.isEmpty() || clean.length() > 500) throw new IllegalArgumentException("Memory must be between 1 and 500 characters.");
        Memory memory = new Memory(UUID.randomUUID(), clean, Instant.now());
        items.add(memory); save(); return memory;
    }

    synchronized Memory forget(int number) throws IOException {
        if (number < 1 || number > items.size()) throw new IllegalArgumentException("Memory " + number + " doesn't exist.");
        Memory removed = items.remove(number - 1); save(); return removed;
    }

    synchronized List<Memory> snapshot() { return List.copyOf(items); }
    synchronized String promptContext() {
        if (items.isEmpty()) return "No approved long-term memories.";
        StringBuilder out = new StringBuilder();
        for (Memory item : items.stream().skip(Math.max(0, items.size() - 12)).toList()) out.append("- ").append(item.text()).append('\n');
        return out.toString();
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\t", 3);
            items.add(new Memory(UUID.fromString(p[0]), new String(Base64.getDecoder().decode(p[2]), StandardCharsets.UTF_8), Instant.parse(p[1])));
        }
    }
    private void save() throws IOException {
        Files.createDirectories(file.getParent()); Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        List<String> lines = items.stream().map(m -> m.id() + "\t" + m.created() + "\t" + Base64.getEncoder().encodeToString(m.text().getBytes(StandardCharsets.UTF_8))).toList();
        Files.write(temp, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
