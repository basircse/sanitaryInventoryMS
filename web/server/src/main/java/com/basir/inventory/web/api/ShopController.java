package com.basir.inventory.web.api;

import com.basir.inventory.web.AppProperties;
import com.basir.inventory.web.db.StateStore;
import com.basir.inventory.web.inventory.InventoryException;
import com.basir.inventory.web.inventory.InventoryService;
import com.basir.inventory.web.model.Models.Category;
import com.basir.inventory.web.model.Models.Item;
import com.basir.inventory.web.model.Models.Movement;
import com.basir.inventory.web.model.Models.Sale;
import com.basir.inventory.web.model.Models.SaleRow;
import com.basir.inventory.web.model.Models.Supplier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Stock, catalog, sales and reports. Every call here needs an active license (see WebConfig). */
@RestController
@RequestMapping("/api")
public class ShopController {
    private final InventoryService inventory;
    private final StateStore store;

    public ShopController(InventoryService inventory, StateStore store) {
        this.inventory = inventory;
        this.store = store;
    }

    // ------------------------------------------------------------ dashboard & lookups

    public record Dashboard(InventoryService.Stats stats, long todaySalesPoisha, List<SaleRow> recent) {}

    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        long startOfToday = LocalDate.now(AppProperties.ZONE).atStartOfDay(AppProperties.ZONE).toInstant().toEpochMilli();
        return new Dashboard(inventory.stats(), inventory.salesTotalSince(startOfToday), inventory.recentSales(8));
    }

    public record Catalog(List<Category> categories, List<Supplier> suppliers, long thresholdMilli) {}

    @GetMapping("/catalog")
    public Catalog catalog() {
        return new Catalog(inventory.categories(), inventory.suppliers(), store.globalThreshold());
    }

    // ------------------------------------------------------------ items

    @GetMapping("/items")
    public List<Item> items() {
        return inventory.items();
    }

    public record ItemDetail(Item item, List<Movement> movements) {}

    @GetMapping("/items/{id}")
    public ItemDetail item(@PathVariable long id) {
        return new ItemDetail(inventory.item(id), inventory.movements(id));
    }

    public record ItemInput(String name, String sku, Long categoryId, Long supplierId, String unit,
                            Long purchasePoisha, Long sellingPoisha, Long qtyMilli, Long thresholdMilli) {
        Item toItem(long id) {
            if (purchasePoisha == null || sellingPoisha == null) throw new InventoryException(InventoryException.Reason.BAD_INPUT);
            return new Item(id, name, sku, categoryId, supplierId, unit, purchasePoisha, sellingPoisha,
                    qtyMilli == null ? 0 : qtyMilli, thresholdMilli, 0, 0);
        }
    }

    public record Saved(long id) {}

    @PostMapping("/items")
    public Saved createItem(@RequestBody ItemInput input) {
        return new Saved(inventory.saveItem(input.toItem(0)));
    }

    @PutMapping("/items/{id}")
    public Saved updateItem(@PathVariable long id, @RequestBody ItemInput input) {
        if (id <= 0) throw new InventoryException(InventoryException.Reason.NOT_FOUND);
        return new Saved(inventory.saveItem(input.toItem(id)));
    }

    @DeleteMapping("/items/{id}")
    public void deleteItem(@PathVariable long id) {
        inventory.deleteItem(id);
    }

    public record QtyInput(Long qtyMilli, String note) {
        long qty() {
            if (qtyMilli == null) throw new InventoryException(InventoryException.Reason.BAD_QTY);
            return qtyMilli;
        }
    }

    @PostMapping("/items/{id}/sell")
    public Sale sell(@PathVariable long id, @RequestBody QtyInput input) {
        return inventory.sell(id, input.qty());
    }

    @PostMapping("/items/{id}/stock-in")
    public void stockIn(@PathVariable long id, @RequestBody QtyInput input) {
        inventory.stockIn(id, input.qty(), input.note());
    }

    @PostMapping("/items/{id}/adjust")
    public void adjust(@PathVariable long id, @RequestBody QtyInput input) {
        inventory.adjustTo(id, input.qty(), input.note());
    }

    // ------------------------------------------------------------ categories & suppliers

    public record CategoryInput(String name, Long parentId) {}

    @PostMapping("/categories")
    public Saved addCategory(@RequestBody CategoryInput input) {
        return new Saved(inventory.addCategory(input.name(), input.parentId()));
    }

    @PutMapping("/categories/{id}")
    public void renameCategory(@PathVariable long id, @RequestBody CategoryInput input) {
        inventory.renameCategory(id, input.name());
    }

    @DeleteMapping("/categories/{id}")
    public void deleteCategory(@PathVariable long id) {
        inventory.deleteCategory(id);
    }

    public record SupplierInput(String name, String phone, String note) {}

    @PostMapping("/suppliers")
    public Saved addSupplier(@RequestBody SupplierInput input) {
        return new Saved(inventory.saveSupplier(0, input.name(), input.phone(), input.note()));
    }

    @PutMapping("/suppliers/{id}")
    public Saved updateSupplier(@PathVariable long id, @RequestBody SupplierInput input) {
        if (id <= 0) throw new InventoryException(InventoryException.Reason.NOT_FOUND);
        return new Saved(inventory.saveSupplier(id, input.name(), input.phone(), input.note()));
    }

    @DeleteMapping("/suppliers/{id}")
    public void deleteSupplier(@PathVariable long id) {
        inventory.deleteSupplier(id);
    }

    // ------------------------------------------------------------ sales & settings

    /** Sales between two shop dates, both inclusive. */
    @GetMapping("/sales")
    public List<SaleRow> sales(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        long fromMs = from.atStartOfDay(AppProperties.ZONE).toInstant().toEpochMilli();
        long toMs = to.plusDays(1).atStartOfDay(AppProperties.ZONE).toInstant().toEpochMilli();
        return inventory.salesBetween(fromMs, toMs);
    }

    public record ThresholdInput(Long milli) {}

    @PutMapping("/settings/threshold")
    public Map<String, Long> setThreshold(@RequestBody ThresholdInput input) {
        if (input.milli() == null || input.milli() < 0) throw new InventoryException(InventoryException.Reason.BAD_INPUT);
        store.setGlobalThreshold(input.milli());
        return Map.of("thresholdMilli", input.milli());
    }
}
