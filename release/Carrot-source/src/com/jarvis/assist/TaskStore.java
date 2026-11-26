package com.jarvis.assist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class TaskStore {
    record Task(UUID id, String text, boolean done, Instant created) { }
    private final Path file;
    private final List<Task> items = new ArrayList<>();
    TaskStore(Path file) throws IOException { this.file = file; load(); }

    synchronized Task add(String text) throws IOException {
        String clean=text.trim(); if(clean.isEmpty()||clean.length()>500) throw new IllegalArgumentException("Task must be between 1 and 500 characters.");
        Task task=new Task(UUID.randomUUID(),clean,false,Instant.now()); items.add(task); save(); return task;
    }
    synchronized Task complete(int number) throws IOException { Task old=get(number); Task updated=new Task(old.id(),old.text(),true,old.created()); items.set(number-1,updated); save(); return updated; }
    synchronized Task delete(int number) throws IOException { Task removed=get(number); items.remove(number-1); save(); return removed; }
    synchronized List<Task> snapshot(){return List.copyOf(items);}
    private Task get(int number){if(number<1||number>items.size())throw new IllegalArgumentException("Task "+number+" doesn't exist.");return items.get(number-1);}
    private void load() throws IOException {if(!Files.exists(file))return;for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){if(line.isBlank())continue;String[] p=line.split("\\t",4);items.add(new Task(UUID.fromString(p[0]),new String(Base64.getDecoder().decode(p[3]),StandardCharsets.UTF_8),Boolean.parseBoolean(p[1]),Instant.parse(p[2])));}}
    private void save() throws IOException {Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp");List<String> lines=items.stream().map(t->t.id()+"\t"+t.done()+"\t"+t.created()+"\t"+Base64.getEncoder().encodeToString(t.text().getBytes(StandardCharsets.UTF_8))).toList();Files.write(temp,lines,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}
}
