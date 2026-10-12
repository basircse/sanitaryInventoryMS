package com.basir.inventory.web.api;

import com.basir.inventory.web.backup.BackupService;
import com.basir.inventory.web.crypto.BackupCrypto.BackupFormatException;
import com.basir.inventory.web.db.StateStore;
import com.basir.inventory.web.model.Models.BackupData;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Backup status, manual backup, download of local backups, and two-step restore. */
@RestController
@RequestMapping("/api")
public class BackupController {
    private static final Pattern NAME = Pattern.compile("Ajwa_Backup_\\d{8}_\\d{6}\\.ajwb");

    private final BackupService backups;
    private final StateStore store;
    /** The uploaded file waiting for the user to confirm the restore (one at a time). */
    private final AtomicReference<Pending> pending = new AtomicReference<>();

    private record Pending(String token, BackupData data, long at) {}

    public BackupController(BackupService backups, StateStore store) {
        this.backups = backups;
        this.store = store;
    }

    public record BackupFile(String name, long size) {}

    public record BackupStatus(long thresholdMilli, Long lastBackupAt, int pendingUploads, List<BackupFile> files) {}

    @GetMapping("/settings")
    public BackupStatus status() throws IOException {
        List<BackupFile> files = new java.util.ArrayList<>();
        for (Path p : backups.localBackups()) files.add(new BackupFile(p.getFileName().toString(), Files.size(p)));
        return new BackupStatus(store.globalThreshold(), store.stateLong(StateStore.LAST_BACKUP), backups.pendingUploads(), files);
    }

    @PostMapping("/backups")
    public BackupFile backupNow() throws IOException {
        Path file = backups.createBackup();
        // Upload in the background; the page doesn't wait for the e-mail relay.
        Thread.ofVirtual().start(backups::uploadPending);
        return new BackupFile(file.getFileName().toString(), Files.size(file));
    }

    @GetMapping("/backups/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        if (!NAME.matcher(name).matches()) return ResponseEntity.notFound().build();
        Path file = backups.backupDir().resolve(name);
        if (!Files.exists(file)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    public record RestorePreview(String token, int items, int sales, long createdAt) {}

    @PostMapping("/restore/preview")
    public RestorePreview preview(@RequestParam("file") MultipartFile file) throws IOException {
        BackupData data = backups.readFile(file.getBytes());
        Pending p = new Pending(UUID.randomUUID().toString(), data, System.currentTimeMillis());
        pending.set(p);
        return new RestorePreview(p.token(), size(data.items()), size(data.sales()),
                data.summary() == null ? 0 : data.summary().createdAt());
    }

    @PostMapping("/restore/{token}")
    public void restore(@PathVariable String token) {
        Pending p = pending.get();
        if (p == null || !p.token().equals(token) || System.currentTimeMillis() - p.at() > 15 * 60_000
                || !pending.compareAndSet(p, null)) {
            throw new BackupFormatException("Choose the file again");
        }
        backups.restore(p.data());
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
