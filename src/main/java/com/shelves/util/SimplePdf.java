package com.shelves.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny PDF writer for simple, text-only tabular documents.
 * <p>
 * Shelves needs to export an inventory sheet as a PDF, and it needs to do so on
 * the user's own machine with no Python, no print server and no heavy PDF
 * library pulled in. A full library like PDFBox would be a large dependency for
 * one plain table. The PDF format's text and layout operators are simple and
 * well documented, so a small hand-written generator that lays out headings and
 * rows of text, paginating when a page fills, covers this one need exactly.
 * <p>
 * This deliberately supports only what the export uses: a title, a subtitle,
 * and rows of monospaced-column text using the standard Helvetica font, which
 * every PDF reader has built in so no font needs embedding. It is not a general
 * PDF toolkit.
 */
public final class SimplePdf {

    private static final float PAGE_WIDTH = 612;   // US Letter, points
    private static final float PAGE_HEIGHT = 792;
    private static final float MARGIN = 54;
    private static final float LINE_HEIGHT = 15;
    private static final float TITLE_SIZE = 18;
    private static final float SUBTITLE_SIZE = 10;
    private static final float BODY_SIZE = 10;

    private final List<String> pageStreams = new ArrayList<>();
    private StringBuilder current;
    private float cursorY;

    private SimplePdf() {
    }

    /**
     * Builds a document and writes it to the given file.
     *
     * @param title    the heading at the top of the first page
     * @param subtitle a smaller line under the title, such as a date and count
     * @param columnHeaders the column titles for the table
     * @param columnX  the x offset (in points from the left margin) of each column
     * @param rows     the table body, each row an array matching the columns
     */
    public static void write(Path file, String title, String subtitle,
                             String[] columnHeaders, float[] columnX,
                             List<String[]> rows) throws IOException {
        SimplePdf pdf = new SimplePdf();
        pdf.build(title, subtitle, columnHeaders, columnX, rows);
        try (OutputStream out = Files.newOutputStream(file)) {
            pdf.writeTo(out);
        }
    }

    private void build(String title, String subtitle, String[] headers,
                       float[] columnX, List<String[]> rows) {
        startPage();

        // Title.
        text(MARGIN, cursorY, TITLE_SIZE, escape(title));
        cursorY -= TITLE_SIZE + 6;

        // Subtitle.
        if (subtitle != null && !subtitle.isBlank()) {
            text(MARGIN, cursorY, SUBTITLE_SIZE, escape(subtitle));
            cursorY -= SUBTITLE_SIZE + 10;
        }

        // A rule under the header block.
        line(MARGIN, cursorY, PAGE_WIDTH - MARGIN, cursorY);
        cursorY -= LINE_HEIGHT;

        // Column headers.
        for (int i = 0; i < headers.length; i++) {
            text(MARGIN + columnX[i], cursorY, BODY_SIZE, escape(headers[i]));
        }
        cursorY -= 4;
        line(MARGIN, cursorY, PAGE_WIDTH - MARGIN, cursorY);
        cursorY -= LINE_HEIGHT;

        // Body rows, paginating when the page is full.
        for (String[] row : rows) {
            if (cursorY < MARGIN + LINE_HEIGHT) {
                startPage();
                // Repeat the column headers at the top of each new page.
                for (int i = 0; i < headers.length; i++) {
                    text(MARGIN + columnX[i], cursorY, BODY_SIZE, escape(headers[i]));
                }
                cursorY -= 4;
                line(MARGIN, cursorY, PAGE_WIDTH - MARGIN, cursorY);
                cursorY -= LINE_HEIGHT;
            }
            for (int i = 0; i < row.length && i < columnX.length; i++) {
                if (row[i] != null && !row[i].isEmpty()) {
                    text(MARGIN + columnX[i], cursorY, BODY_SIZE, escape(row[i]));
                }
            }
            cursorY -= LINE_HEIGHT;
        }

        finishPage();
    }

    private void startPage() {
        if (current != null) {
            finishPage();
        }
        current = new StringBuilder();
        cursorY = PAGE_HEIGHT - MARGIN;
    }

    private void finishPage() {
        if (current != null) {
            pageStreams.add(current.toString());
            current = null;
        }
    }

    /** Draws a line of text at a position, in the chosen size. */
    private void text(float x, float y, float size, String content) {
        current.append("BT\n")
               .append("/F1 ").append(trim(size)).append(" Tf\n")
               .append(trim(x)).append(' ').append(trim(y)).append(" Td\n")
               .append('(').append(content).append(") Tj\n")
               .append("ET\n");
    }

    private void line(float x1, float y1, float x2, float y2) {
        current.append("0.7 0.7 0.7 RG\n0.5 w\n")
               .append(trim(x1)).append(' ').append(trim(y1)).append(" m\n")
               .append(trim(x2)).append(' ').append(trim(y2)).append(" l\nS\n")
               .append("0 0 0 RG\n");
    }

    /**
     * Assembles the PDF file structure around the page content streams.
     * <p>
     * A PDF is a set of numbered objects followed by a cross-reference table of
     * their byte offsets. The objects here are the catalog, the page tree, one
     * font, and for each page a page object and its content stream.
     */
    private void writeTo(OutputStream out) throws IOException {
        List<Integer> offsets = new ArrayList<>();

        int pageCount = pageStreams.size();

        // Object 1: catalog. Object 2: page tree. Object 3: font.
        // Pages start at object 4, content streams interleaved after them.
        List<Integer> pageObjectIds = new ArrayList<>();
        List<Integer> contentObjectIds = new ArrayList<>();
        int nextId = 4;
        for (int i = 0; i < pageCount; i++) {
            pageObjectIds.add(nextId++);
            contentObjectIds.add(nextId++);
        }

        StringBuilder pdf = new StringBuilder();
        pdf.append("%PDF-1.4\n");

        // 1: Catalog.
        offsets.add(pdf.length());
        pdf.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        // 2: Page tree.
        offsets.add(pdf.length());
        pdf.append("2 0 obj\n<< /Type /Pages /Count ").append(pageCount).append(" /Kids [");
        for (int id : pageObjectIds) {
            pdf.append(id).append(" 0 R ");
        }
        pdf.append("] >>\nendobj\n");

        // 3: Font.
        offsets.add(pdf.length());
        pdf.append("3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

        // Page objects and their content streams.
        for (int i = 0; i < pageCount; i++) {
            int pageId = pageObjectIds.get(i);
            int contentId = contentObjectIds.get(i);
            String stream = pageStreams.get(i);

            offsets.add(pdf.length());
            pdf.append(pageId).append(" 0 obj\n")
               .append("<< /Type /Page /Parent 2 0 R ")
               .append("/MediaBox [0 0 ").append(trim(PAGE_WIDTH)).append(' ')
               .append(trim(PAGE_HEIGHT)).append("] ")
               .append("/Resources << /Font << /F1 3 0 R >> >> ")
               .append("/Contents ").append(contentId).append(" 0 R >>\nendobj\n");

            offsets.add(pdf.length());
            byte[] streamBytes = stream.getBytes(StandardCharsets.ISO_8859_1);
            pdf.append(contentId).append(" 0 obj\n")
               .append("<< /Length ").append(streamBytes.length).append(" >>\nstream\n")
               .append(stream)
               .append("endstream\nendobj\n");
        }

        // Cross-reference table.
        int xrefStart = pdf.length();
        int objectCount = 3 + pageCount * 2;
        pdf.append("xref\n0 ").append(objectCount + 1).append('\n');
        pdf.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }

        // Trailer.
        pdf.append("trailer\n<< /Size ").append(objectCount + 1)
           .append(" /Root 1 0 R >>\nstartxref\n")
           .append(xrefStart).append("\n%%EOF");

        out.write(pdf.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Escapes the characters that are special inside a PDF string literal. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '(', ')', '\\' -> escaped.append('\\').append(c);
                case '\n' -> escaped.append(' ');
                case '\r' -> { }
                default -> {
                    if (c <= 126) {
                        // Printable ASCII, written directly.
                        escaped.append(c);
                    } else if (c <= 255) {
                        // Latin-1. The standard Helvetica font's default
                        // encoding covers this range, but the bytes have to be
                        // written as octal escapes so they survive as single
                        // bytes rather than being mangled by text encoding.
                        escaped.append('\\')
                               .append(String.format("%03o", (int) c));
                    } else {
                        // Outside Latin-1: no glyph in the standard font, so a
                        // plain hyphen rather than a broken box.
                        escaped.append('-');
                    }
                }
            }
        }
        return escaped.toString();
    }

    /** Formats a coordinate without a needless trailing ".0". */
    private static String trim(float value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}
