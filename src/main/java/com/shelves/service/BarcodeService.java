package com.shelves.service;

import com.shelves.db.Database;
import com.shelves.db.dao.ProductCacheDao;
import com.shelves.db.dao.ProductCacheDao.CachedProduct;

import java.util.Optional;

/**
 * Turns a barcode into product details, using only what the user has saved.
 * <p>
 * There is deliberately no external lookup service here. An online product
 * database like Open Food Facts covers groceries and almost nothing else, so it
 * returns nothing for medicine, cosmetics, hardware or supplies, which is most
 * of what a general inventory tool holds. Rather than half a feature that works
 * for food and silently fails elsewhere, barcodes are resolved against a local
 * table the user builds themselves.
 * <p>
 * The flow is: scan a barcode, and if it has been seen before its saved details
 * fill the form. If it is new, the user enters the details once by hand and can
 * save them against that barcode, so the next scan of the same product fills in
 * automatically. This works for every category, needs no network, and keeps the
 * data accurate because it is the user's own.
 * <p>
 * Scanning itself needs no special support. The common USB scanners present as
 * keyboards: they type the digits into whatever field has focus and press
 * Enter. So the barcode field in the item form is an ordinary text field whose
 * Enter key runs a lookup, which works with a scanner and with someone typing
 * the number off the box by hand.
 */
public class BarcodeService {

    private final Database database;
    private final ProductCacheDao cacheDao = new ProductCacheDao();

    public BarcodeService(Database database) {
        this.database = database;
    }

    /** True if the text looks like a barcode: 6 to 14 digits. */
    public static boolean isPlausibleBarcode(String value) {
        return value != null && value.trim().matches("\\d{6,14}");
    }

    /**
     * Finds a barcode in the user's saved products.
     *
     * @return the product if it has been saved before, otherwise empty, meaning
     *         the user should enter the details and can save them for next time
     */
    public Optional<CachedProduct> lookup(String barcode) {
        if (!isPlausibleBarcode(barcode)) {
            return Optional.empty();
        }
        return database.query("check your saved products",
                conn -> cacheDao.find(conn, barcode.trim()));
    }

    /**
     * Saves product details against a barcode, so a future scan fills them in.
     * Called when the user opts to remember a product they entered by hand.
     */
    public void remember(String barcode, String name, String brand, String unit) {
        if (!isPlausibleBarcode(barcode)) {
            return;
        }
        CachedProduct product = new CachedProduct(barcode.trim(), name, blankToNull(brand),
                blankToNull(unit));
        database.transact("remember that product", conn -> cacheDao.save(conn, product));
    }

    /** True if the user has already saved details for this barcode. */
    public boolean isKnown(String barcode) {
        return lookup(barcode).isPresent();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
