package com.jarvis.assist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

final class AuditLog {
    private final Path file;
    AuditLog(Path file){this.file=file;}
    synchronized void write(String type,String detail,boolean success){
        try{Files.createDirectories(file.getParent());String safe=detail.replace("\\","\\\\").replace("\"","\\\"").replace("\r"," ").replace("\n"," ");String line="{\"time\":\""+Instant.now()+"\",\"type\":\""+type+"\",\"success\":"+success+",\"detail\":\""+safe+"\"}\n";Files.writeString(file,line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException ignored){}
    }
}
