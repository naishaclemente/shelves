package com.shelves.model;

/**
 * A named group of items, such as Kitchen or Stadium Concession.
 * <p>
 * Exactly one shelf is the default shelf ("Master Shelf"). It is not a real
 * container: nothing is ever assigned to it. It is a saved view meaning
 * "everything", which is why it can never be renamed or deleted and why an
 * item's shelf assignment is unaffected by it.
 */
public class Shelf {

    private int id;
    private String name;
    private boolean defaultShelf;
    private int itemCount;

    public Shelf() {
    }

    public Shelf(int id, String name, boolean defaultShelf) {
        this.id = id;
        this.name = name;
        this.defaultShelf = defaultShelf;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isDefaultShelf() {
        return defaultShelf;
    }

    public void setDefaultShelf(boolean defaultShelf) {
        this.defaultShelf = defaultShelf;
    }

    /** Populated by queries that ask for it; not stored on the row. */
    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }

    @Override
    public String toString() {
        return name;
    }
}
