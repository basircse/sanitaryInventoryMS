package com.basir.inventory.web.api;

import com.basir.inventory.web.AppProperties;
import com.basir.inventory.web.db.StateStore;
import com.basir.inventory.web.license.LicenseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/** Open without a license: the lock screen needs the device code and the activation call. */
@RestController
@RequestMapping("/api/license")
public class LicenseController {
    private final LicenseService license;
    private final StateStore store;
    private final AppProperties props;

    public LicenseController(LicenseService license, StateStore store, AppProperties props) {
        this.license = license;
        this.store = store;
        this.props = props;
    }

    public record LicenseInfo(LicenseService.Status status, String shopName, String providerPhone, String providerWhatsapp, String version) {}

    public record ActivateRequest(String key) {}

    public record Activated(long serial, long expiresAt, String backupMode) {}

    /** Called when the app is opened in a browser; also records "last opened" for the provider's sheet. */
    @GetMapping
    public LicenseInfo info() {
        long now = System.currentTimeMillis();
        Long last = store.stateLong(StateStore.LAST_OPENED);
        if (last == null || now - last > TimeUnit.MINUTES.toMillis(5)) store.putState(StateStore.LAST_OPENED, now);
        return new LicenseInfo(license.refresh(), props.shopName(), props.providerPhone(), props.providerWhatsapp(), props.version());
    }

    @PostMapping("/activate")
    public Activated activate(@RequestBody ActivateRequest request) {
        LicenseService.LicenseRow row = license.activate(request.key() == null ? "" : request.key());
        return new Activated(row.serial(), row.expiresAt(), row.mode().name());
    }
}
