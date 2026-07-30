package com.shelves.ui;

import com.shelves.model.HistoryKind;
import com.shelves.model.Item;
import com.shelves.model.PricePoint;
import com.shelves.service.InventoryService;
import com.shelves.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.OptionalDouble;

/**
 * One item's history, shown as two sections: how its price has moved over time,
 * and when stock came in or went out.
 * <p>
 * This lives inside the edit dialog rather than being its own window. Both
 * charts are drawn by hand from plain shapes, which keeps the project free of a
 * charting dependency and gives exactly the axes and labels these two views
 * need. The panel takes a snapshot of the log when it is built; it does not
 * update live, which is fine for a dialog that is opened, read, and closed.
 */
public class ItemHistoryPanel extends ScrollPane {

    private static final DateTimeFormatter EXACT =
            DateTimeFormatter.ofPattern("d MMM ''yy");

    private final InventoryService inventory;
    private final Item item;

    // Chart colours, resolved once from the active theme. Canvas drawing cannot
    // read the stylesheet, so these mirror the CSS variables for the two themes.
    // The donut wedges reuse the theme's status palette: green for used, amber
    // for expired, and a muted grey for the derived unused remainder. Surface is
    // the panel background, used to punch the donut's hole.
    private final Color axisColor;
    private final Color mutedColor;
    private final Color labelColor;
    private final Color surfaceColor;
    private final Color usedColor;
    private final Color expiredColor;
    private final Color unusedColor;

    public ItemHistoryPanel(InventoryService inventory, Item item) {
        this.inventory = inventory;
        this.item = item;

        boolean dark = ThemeManager.isCurrentThemeDark();
        this.axisColor    = Color.web(dark ? "#2E323C" : "#E8E0D0");
        this.mutedColor   = Color.web(dark ? "#9A968A" : "#6B6659");
        this.labelColor   = Color.web(dark ? "#EDEBE3" : "#2C2A26");
        this.surfaceColor = Color.web(dark ? "#21252E" : "#FFFFFF");
        this.usedColor    = Color.web(dark ? "#4ea86f" : "#3F8F5C");
        this.expiredColor = Color.web(dark ? "#d9a441" : "#B0741F");
        this.unusedColor  = Color.web(dark ? "#6B675C" : "#8A8577");

        setFitToWidth(true);
        getStyleClass().add("history-panel");
        setContent(build());
    }

    private Region build() {
        List<PricePoint> all = inventory.priceHistoryFor(item);
        List<PricePoint> purchases = all.stream().filter(PricePoint::isPurchase).toList();

        VBox content = new VBox(16);
        content.setPadding(new Insets(14));

        content.getChildren().add(sectionHeader("Price history"));
        content.getChildren().add(buildPriceSection(purchases));

        content.getChildren().add(sectionHeader("Usage Tracker"));
        content.getChildren().add(buildUsageSection(all));

        return content;
    }

    private Label sectionHeader(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("history-section-header");
        return label;
    }

    // ==================== PRICE ====================

    private Region buildPriceSection(List<PricePoint> purchases) {
        if (purchases.isEmpty()) {
            return note("No purchases recorded yet. Tick \u201cTrack this price over time\u201d "
                    + "on the item, and each purchase will appear here.");
        }

        long latest = purchases.get(0).getPriceCents();
        OptionalDouble average = inventory.averagePriceFor(item);
        String summary = "Latest " + Money.format(latest);
        if (average.isPresent()) {
            summary += "     Average " + Money.format(Math.round(average.getAsDouble()));
        }
        summary += "     " + purchases.size()
                + (purchases.size() == 1 ? " purchase" : " purchases");

        Label summaryLabel = new Label(summary);
        summaryLabel.getStyleClass().add("history-summary");

        // Newest first, matching the summary's "latest" figure at the top.
        List<PricePoint> rows = purchases.stream()
                .sorted((a, b) -> b.getRecordedOn().compareTo(a.getRecordedOn()))
                .toList();

        TableView<PricePoint> table = buildPriceTable(rows);

        VBox box = new VBox(8, table, summaryLabel);
        return box;
    }

    /**
     * The purchase history as a compact spreadsheet: date, quantity, unit price,
     * and line total. Its body is capped at a fixed number of visible rows with a
     * scrollbar for the rest, so the panel's height stays stable no matter how
     * many purchases accumulate — the list used to grow without bound and push
     * the Usage Tracker off-screen.
     */
    private TableView<PricePoint> buildPriceTable(List<PricePoint> rows) {
        TableView<PricePoint> table = new TableView<>();
        table.getStyleClass().add("history-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<PricePoint, String> date = priceCol("Date", p ->
                p.getRecordedOn() == null ? "\u2014" : EXACT.format(p.getRecordedOn()));
        TableColumn<PricePoint, String> qty = priceCol("Qty", PricePoint::quantityDisplay);
        TableColumn<PricePoint, String> unit = priceCol("Unit price", PricePoint::unitPriceDisplay);
        TableColumn<PricePoint, String> total = priceCol("Total", p ->
                Money.format(Math.round(p.getPriceCents() * p.getQuantity())));

        table.getColumns().add(date);
        table.getColumns().add(qty);
        table.getColumns().add(unit);
        table.getColumns().add(total);
        table.getItems().setAll(rows);

        // Cap the visible body at about five rows; the rest scroll. Header plus
        // five rows at the app's row height keeps the pane a fixed size.
        double rowHeight = 32;
        double headerHeight = 30;
        int visibleRows = 5;
        double capped = headerHeight + rowHeight * visibleRows + 2;
        table.setPrefHeight(capped);
        table.setMinHeight(capped);
        table.setMaxHeight(capped);
        table.setFixedCellSize(rowHeight);
        return table;
    }

    private TableColumn<PricePoint, String> priceCol(
            String heading, java.util.function.Function<PricePoint, String> reader) {
        TableColumn<PricePoint, String> column = new TableColumn<>(heading);
        column.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(reader.apply(data.getValue())));
        column.setSortable(false);
        return column;
    }

    // ==================== USAGE ====================

    private Region buildUsageSection(List<PricePoint> all) {
        if (all.isEmpty()) {
            return note("No stock movements recorded yet. Purchases, use and waste will "
                    + "appear here once this item is tracked.");
        }

        // Oldest first is no longer needed for a timeline; the donut is a
        // proportional summary, not a sequence.
        List<PricePoint> series = all;

        // The breakdown reconciles by quantity, not by number of events:
        // everything bought is either still on hand, used, or thrown out.
        double bought = sumQuantity(series, HistoryKind.PURCHASE);
        double used = sumQuantity(series, HistoryKind.USED);
        double expired = sumQuantity(series, HistoryKind.EXPIRED);

        // Unused is derived, never logged: it is simply what is left once used
        // and expired are taken off what was bought — roughly the quantity on
        // hand. Deriving it live keeps it consistent with the other two by
        // construction, so the three can never drift out of Bought = Used +
        // Expired + Unused. Clamped at zero so a partial log cannot show a
        // negative remainder.
        double unused = Math.max(0, bought - used - expired);

        Canvas donut = drawUsageDonut(bought, used, expired, unused);
        Region legend = usageLegend(used, expired, unused);

        HBox row = new HBox(24, new StackPane(donut), legend);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private double sumQuantity(List<PricePoint> series, HistoryKind kind) {
        return series.stream()
                .filter(p -> p.getKind() == kind)
                .mapToDouble(PricePoint::getQuantity)
                .sum();
    }

    /** A quantity without a needless trailing ".0". */
    private String qty(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    /**
     * The usage breakdown as a three-wedge donut: used, expired, and the derived
     * unused remainder, sized in proportion to what was bought, with the bought
     * total in the centre. A donut reads the split at a glance far better than
     * the old timeline bars did, and the identity Bought = Used + Expired +
     * Unused is exactly what the three wedges add up to.
     */
    private Canvas drawUsageDonut(double bought, double used, double expired, double unused) {
        double size = 150;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext g = canvas.getGraphicsContext2D();

        double cx = size / 2;
        double cy = size / 2;
        double outer = size / 2 - 4;
        double thickness = 22;
        double inner = outer - thickness;

        double total = bought <= 0 ? 1 : bought;
        // JavaFX arcs sweep counter-clockwise from 0° at 3 o'clock; start at the
        // top (90°) and go clockwise with negative extents for a natural read.
        double start = 90;
        double[] values = {used, expired, unused};
        Color[] colours = {usedColor, expiredColor, unusedColor};

        boolean anyDrawn = false;
        for (int i = 0; i < values.length; i++) {
            if (values[i] <= 0) {
                continue;
            }
            double extent = 360.0 * (values[i] / total);
            g.setFill(colours[i]);
            g.fillArc(cx - outer, cy - outer, outer * 2, outer * 2,
                    start, -extent, javafx.scene.shape.ArcType.ROUND);
            start -= extent;
            anyDrawn = true;
        }
        if (!anyDrawn) {
            // Nothing bought yet: a faint ring so the donut is not just blank.
            g.setFill(axisColor);
            g.fillArc(cx - outer, cy - outer, outer * 2, outer * 2, 0, 360,
                    javafx.scene.shape.ArcType.ROUND);
        }

        // Punch the hole to make it a donut, in the panel's own background.
        g.setFill(surfaceColor);
        g.fillOval(cx - inner, cy - inner, inner * 2, inner * 2);

        // Centre label: the bought total, with a small caption under it.
        g.setFill(labelColor);
        g.setFont(Font.font(null, javafx.scene.text.FontWeight.BOLD, 24));
        g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        g.setTextBaseline(javafx.geometry.VPos.CENTER);
        g.fillText(qty(bought), cx, cy - 6);
        g.setFill(mutedColor);
        g.setFont(Font.font(11));
        g.fillText("bought", cx, cy + 12);
        return canvas;
    }

    private Region usageLegend(double used, double expired, double unused) {
        VBox legend = new VBox(10,
                legendRow(usedColor, "Used", used),
                legendRow(expiredColor, "Expired", expired),
                legendRow(unusedColor, "Unused", unused));
        legend.setAlignment(Pos.CENTER_LEFT);
        return legend;
    }

    private Region legendRow(Color colour, String label, double count) {
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(5, colour);
        Label name = new Label(label);
        name.getStyleClass().add("legend-label");
        name.setPrefWidth(64);
        Label value = new Label(qty(count));
        value.getStyleClass().add("legend-value");
        HBox row = new HBox(10, dot, name, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("history-summary");
        return label;
    }
}
