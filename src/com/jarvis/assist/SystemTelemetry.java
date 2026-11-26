package com.jarvis.assist;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.NetworkInterface;
import java.nio.file.*;
import java.util.Collections;

final class SystemTelemetry {
    record Snapshot(double cpuPercent, double memoryPercent, long memoryUsed, long memoryTotal,
                    double diskPercent, long diskFree, long uptimeSeconds, long processCount, boolean networkOnline) { }

    Snapshot read() {
        double cpu=0,memory=0,disk=0; long used=0,total=0,free=0;
        try {
            com.sun.management.OperatingSystemMXBean os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
            cpu=Math.max(0,os.getCpuLoad()*100); total=os.getTotalMemorySize(); used=total-os.getFreeMemorySize(); memory=total==0?0:used*100d/total;
        } catch(Exception ignored) { }
        try { FileStore store=Files.getFileStore(Path.of(System.getenv().getOrDefault("SystemDrive","C:")+"\\")); long size=store.getTotalSpace(); free=store.getUsableSpace(); disk=size==0?0:(size-free)*100d/size; } catch(IOException ignored) { }
        boolean online=false;
        try { online=Collections.list(NetworkInterface.getNetworkInterfaces()).stream().anyMatch(n->{try{return n.isUp()&&!n.isLoopback();}catch(Exception e){return false;}}); } catch(Exception ignored) { }
        return new Snapshot(round(cpu),round(memory),used,total,round(disk),free,ManagementFactory.getRuntimeMXBean().getUptime()/1000,ProcessHandle.allProcesses().count(),online);
    }
    private static double round(double v){return Math.round(v*10d)/10d;}
}
