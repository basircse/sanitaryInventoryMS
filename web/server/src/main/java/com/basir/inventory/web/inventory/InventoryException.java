package com.basir.inventory.web.inventory;

/** Failure the UI turns into a message. */
public class InventoryException extends RuntimeException {
    public enum Reason { NOT_ENOUGH_STOCK, SKU_TAKEN, NAME_TAKEN, IN_USE, HAS_SALES, NOT_FOUND, BAD_QTY, BAD_INPUT }

    public final Reason reason;

    public InventoryException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
}
