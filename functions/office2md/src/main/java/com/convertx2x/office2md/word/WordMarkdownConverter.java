package com.convertx2x.office2md.word;

import static com.convertx2x.office2md.word.WordXml.*;
import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionResult;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.Markdown;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFAbstractFootnoteEndnote;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** DOCX semantic conversion. Does not evaluate fields, update links, or calculate pagination. */
public final class WordMarkdownConverter {
    private static final String DOCUMENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private final ConversionLimits limits;
    public WordMarkdownConverter(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public ConversionResult convert(byte[] input, String filename) {
        if (input == null || input.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "入力ファイルが空です。");
        if (input.length > limits.maxInputBytes()) throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えています。");
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".docx"))
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Word の .docx ファイルを指定してください。");
        FileMagic magic;
        try { magic = FileMagic.valueOf(input); }
        catch (IllegalArgumentException e) { throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Word の形式を確認できません。"); }
        if (magic == FileMagic.OLE2) {
            try (POIFSFileSystem file = new POIFSFileSystem(new ByteArrayInputStream(input))) {
                if (file.getRoot().hasEntry("EncryptedPackage"))
                    throw new ConversionException(422, "ENCRYPTED_DOCUMENT", "暗号化された Word は変換できません。");
            } catch (ConversionException e) { throw e; }
            catch (Exception e) { throw new ConversionException(422, "INVALID_DOCUMENT", "Word ファイルを読み取れませんでした。"); }
        }
        if (magic != FileMagic.OOXML)
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Word の .docx ファイルを指定してください。");
        ConversionWorkspace workspace = new ConversionWorkspace(limits);
        try {
            Path source = workspace.directory().resolve("input.docx");
            Files.write(source, input);
            try (OPCPackage pkg = OPCPackage.open(source.toFile(), PackageAccess.READ);
                    XWPFDocument document = new XWPFDocument(pkg)) {
                if (!DOCUMENT_TYPE.equals(document.getPackagePart().getContentType()))
                    throw new ConversionException(415, "UNSUPPORTED_FORMAT", "マクロを含まない .docx ファイルを指定してください。");
                for (PackagePart part : pkg.getParts()) {
                    String type = part.getContentType().toLowerCase(Locale.ROOT);
                    String path = part.getPartName().getName().toLowerCase(Locale.ROOT);
                    if (type.contains("vba") || type.contains("macroenabled") || path.contains("vbaproject"))
                        throw new ConversionException(415, "UNSUPPORTED_FORMAT", "マクロを含む Word には対応していません。");
                }
                workspace.sectionIncluded();
                Reader reader = new Reader(document, workspace);
                String markdown = reader.convert();
                byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > limits.maxMarkdownBytes()) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdown のサイズが上限を超えています。");
                workspace.write("document.md", bytes);
                workspace.finishReport(filename, ConversionWorkspace.sha256(input));
            }
            Files.deleteIfExists(source);
            return new ConversionResult(workspace);
        } catch (ConversionException e) {
            workspace.close(); throw e;
        } catch (EncryptedDocumentException e) {
            workspace.close(); throw new ConversionException(422, "ENCRYPTED_DOCUMENT", "暗号化された Word は変換できません。");
        } catch (Exception e) {
            workspace.close();
            // Parser exceptions can contain XML or external relationship text; keep them out of client/worker errors.
            throw new ConversionException(422, "INVALID_DOCUMENT", "Word ファイルを読み取れませんでした。");
        }
    }

    private static final class Reader {
        /** XML 1.0 cannot contain this noncharacter, so source text cannot collide with it. */
        private static final String PAGE_BREAK = "\uFDD0";
        private final XWPFDocument document;
        private final ConversionWorkspace workspace;
        private final WordStyles styles;
        private final WordNumbering numbering;
        private final WordDrawings drawings;
        private final String section = "document";
        private final StringBuilder output = new StringBuilder();
        private final LinkedHashMap<String, Note> notes = new LinkedHashMap<>();
        private final Set<String> noticeKeys = new HashSet<>();
        private final Deque<Boolean> fieldResults = new ArrayDeque<>();
        private int paragraphNumber;
        private int tableNumber;
        private long tableCells;
        private long readItems;
        private long textCharacters;
        private int outputLine = 1;
        private int pageNumber = 1;
        private boolean documentContent;
        private record Note(String key, int id, boolean endnote) { }
        private record Visit(Node node, int depth) { }

        Reader(XWPFDocument document, ConversionWorkspace workspace) {
            this.document = document; this.workspace = workspace;
            styles = new WordStyles(document);
            numbering = new WordNumbering(document, styles, workspace);
            drawings = new WordDrawings(workspace);
        }
        String convert() {
            Node body = document.getDocument().getBody().getDomNode();
            checkTree(body);
            append(output, "[page 1]\n\n");
            outputLine = 3;
            body(body, document.getPackagePart(), output, false);
            Set<String> emitted = new HashSet<>();
            // A note may reference another note; each definition is emitted once and all limits are shared.
            while (emitted.size() < notes.size()) {
                Note note = notes.values().stream().filter(n -> !emitted.contains(n.key())).findFirst().orElseThrow();
                emitted.add(note.key());
                XWPFAbstractFootnoteEndnote source = note.endnote() ? document.getEndnoteByID(note.id()) : document.getFootnoteByID(note.id());
                if (source == null) {
                    notice("MISSING_NOTE", note.key(), "参照先の脚注が見つかりません。", false);
                    append(output, "[^" + note.key() + "]: （参照先なし）\n\n");
                    continue;
                }
                Node node = source.getCTFtnEdn().getDomNode();
                checkTree(node);
                StringBuilder content = new StringBuilder();
                fieldResults.clear();
                body(node, source.getOwner().getPackagePart(), content, true);
                String text = content.toString().strip();
                append(output, "[^" + note.key() + "]: " + text.replace("\n", "\n    ") + "\n\n");
            }
            return output.toString();
        }
        private void checkTree(Node root) {
            Deque<Visit> pending = new ArrayDeque<>();
            pending.push(new Visit(root, 0));
            while (!pending.isEmpty()) {
                Visit visit = pending.pop();
                if (visit.depth() > 128) throw ConversionWorkspace.limit("DOCUMENT_DEPTH_LIMIT", "Word の XML の入れ子が上限を超えています。");
                if (ConversionLimits.exceeds(++readItems, workspace.limits().maxReadItems())) throw ConversionWorkspace.limit("READ_ITEMS_LIMIT", "読み取り要素数が上限を超えています。");
                // Count without inspecting deleted text or relationship values.
                for (Node n = visit.node().getLastChild(); n != null; n = n.getPreviousSibling())
                    if (n.getNodeType() == Node.ELEMENT_NODE) pending.push(new Visit(n, visit.depth() + 1));
            }
        }
        private void body(Node parent, PackagePart part, StringBuilder destination, boolean note) {
            for (Node node : children(parent)) {
                if (omitted(node)) continue;
                if (is(node, "p")) {
                    String range = "paragraph:" + (++paragraphNumber);
                    String text = paragraph(node, part, range, false, !note);
                    if (!text.isBlank()) emit(destination, text, "paragraph", range, note);
                } else if (is(node, "tbl")) {
                    String range = "table:" + (++tableNumber);
                    String text = table(node, part, range);
                    if (!text.isBlank()) emit(destination, text, "table", range, note);
                } else if (is(node, "sdt")) body(child(node, "sdtContent"), part, destination, note);
                else if (is(node, "ins") || is(node, "moveTo") || is(node, "customXml") || is(node, "sdtContent"))
                    body(node, part, destination, note);
                else if (is(node, "altChunk")) {
                    notice("ALTERNATIVE_CONTENT_UNSUPPORTED", "document", "代替形式の挿入文書は読み込みません。", false);
                    emit(destination, "（挿入文書は未対応）", "unsupported", "document", note);
                }
            }
        }
        private void emit(StringBuilder destination, String text, String type, String range, boolean note) {
            if (!note && text.contains(PAGE_BREAK)) {
                String[] pages = text.split(PAGE_BREAK, -1);
                for (int index = 0; index < pages.length; index++) {
                    if (index > 0) nextPage(destination);
                    if (!pages[index].isBlank()) emit(destination, pages[index], type, range, false);
                }
                return;
            }
            int line = outputLine;
            append(destination, text.stripTrailing() + "\n\n");
            if (!note) {
                int lines = (int) text.stripTrailing().chars().filter(c -> c == '\n').count();
                workspace.block(Map.of("type", type, "section", section, "range", range,
                        "markdownStartLine", line, "markdownEndLine", line + lines));
                outputLine += lines + 2;
                documentContent = true;
            }
        }
        private void nextPage(StringBuilder destination) {
            // A break-before marker on the first paragraph still starts page 1.
            if (!documentContent && pageNumber == 1) return;
            pageNumber++;
            append(destination, "[page " + pageNumber + "]\n\n");
            outputLine += 2;
        }
        private String paragraph(Node p, PackagePart part, String range, boolean cell, boolean pages) {
            String prefix = numbering.prefix(p, section, range);
            StringBuilder text = new StringBuilder();
            inlineChildren(p, p, part, range, text, false, pages);
            String result = text.toString();
            boolean breakBefore = pages && on(child(child(p, "pPr"), "pageBreakBefore"));
            if (result.isBlank()) return breakBefore ? PAGE_BREAK : "";
            int heading = styles.heading(p);
            if (!cell && heading > 0) result = "#".repeat(heading) + " " + result;
            return (breakBefore ? PAGE_BREAK : "") + prefix + result;
        }
        private void inlineChildren(Node parent, Node paragraph, PackagePart part, String range, StringBuilder out, boolean plain, boolean pages) {
            for (Node node : children(parent)) inline(node, paragraph, part, range, out, plain, pages);
        }
        private void inline(Node node, Node paragraph, PackagePart part, String range, StringBuilder out, boolean plain, boolean pages) {
            if (omitted(node)) return;
            if (is(node, "r")) {
                WordStyles.RunStyle style = styles.run(paragraph, node);
                if (style.strike() || style.hidden()) {
                    if (!plain) notice(style.strike() ? "STRIKETHROUGH_REMOVED" : "HIDDEN_TEXT_REMOVED", range,
                            style.strike() ? "取消線の文字を除外しました。" : "非表示の文字を除外しました。", true);
                    // Keep structural markers, but never read the removed instructions or text.
                    for (Node child : children(node)) {
                        if (is(child, "fldChar")) field(child, range, plain);
                        else if (pages && fieldVisible() && (is(child, "lastRenderedPageBreak")
                                || is(child, "br") && "page".equals(attr(child, "type")))) append(out, PAGE_BREAK);
                    }
                    return;
                }
                for (Node child : children(node)) {
                    if (is(child, "t")) {
                        if (!fieldVisible()) continue;
                        String value = text(child);
                        textCharacters += value.length();
                        if (textCharacters > workspace.limits().maxMarkdownBytes()) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "文字量が上限を超えています。");
                        String text = plain ? value : Markdown.escape(value);
                        append(out, !plain && style.bold() ? Markdown.bold(text) : text);
                    } else inline(child, paragraph, part, range, out, plain, pages);
                }
            } else if (is(node, "hyperlink")) {
                StringBuilder label = new StringBuilder();
                inlineChildren(node, paragraph, part, range, label, plain, pages);
                if (label.isEmpty()) return;
                if (plain) { append(out, label.toString()); return; }
                String id = node instanceof Element e ? e.getAttributeNS(R, "id") : "";
                var relationship = id.isEmpty() ? null : part.getRelationship(id);
                if (relationship != null && relationship.getTargetMode() == TargetMode.EXTERNAL
                        && Markdown.safeLink(relationship.getTargetURI().toString()))
                    append(out, Markdown.link(label.toString(), relationship.getTargetURI().toString()));
                else {
                    notice("UNSUPPORTED_LINK", range, "内部・ファイルなどのリンク先を除外し、表示文字を保持しました。", false);
                    append(out, label.toString());
                }
            } else if (is(node, "fldChar")) field(node, range, plain);
            else if (is(node, "instrText") || is(node, "delText") || is(node, "rPr") || is(node, "pPr")
                    || is(node, "commentReference") || is(node, "footnoteRef") || is(node, "endnoteRef")) { /* metadata / instructions */ }
            else if (is(node, "fldSimple")) {
                if (!plain) notice("FIELD_CACHED_RESULT", range, "フィールドは実行せず、保存済みの表示だけを保持しました。", true);
                inlineChildren(node, paragraph, part, range, out, plain, pages);
            } else if (is(node, "br")) {
                if (fieldVisible()) append(out, pages && "page".equals(attr(node, "type")) ? PAGE_BREAK : "\n");
            } else if (is(node, "lastRenderedPageBreak")) {
                if (fieldVisible() && pages) append(out, PAGE_BREAK);
            } else if (is(node, "cr")) { if (fieldVisible()) append(out, "\n"); }
            else if (is(node, "tab")) { if (fieldVisible()) append(out, "    "); }
            else if (is(node, "noBreakHyphen")) { if (fieldVisible()) append(out, plain ? "‑" : Markdown.escape("‑")); }
            else if (is(node, "softHyphen")) { /* discretionary line break is unnecessary in Markdown */ }
            else if (is(node, "footnoteReference") || is(node, "endnoteReference")) {
                if (plain || !fieldVisible()) return;
                int id = integer(attr(node, "id"), Integer.MIN_VALUE);
                if (id < 0) return;
                boolean end = is(node, "endnoteReference");
                String key = (end ? "endnote-" : "footnote-") + id;
                notes.putIfAbsent(key, new Note(key, id, end));
                append(out, "[^" + key + "]");
            } else if (is(node, "drawing") || is(node, "pict") || is(node, "object")) {
                if (!plain && fieldVisible()) append(out, drawings.render(node, part, section, range, this::visibleText));
            } else if (is(node, "sdt")) inlineChildren(child(node, "sdtContent"), paragraph, part, range, out, plain, pages);
            else if ("http://schemas.openxmlformats.org/markup-compatibility/2006".equals(node.getNamespaceURI())
                    && "AlternateContent".equals(node.getLocalName())) {
                Node chosen = null;
                for (Node candidate : children(node)) {
                    if ("Choice".equals(candidate.getLocalName())) { chosen = candidate; break; }
                    if ("Fallback".equals(candidate.getLocalName())) chosen = candidate;
                }
                inlineChildren(chosen, paragraph, part, range, out, plain, pages);
            }
            else if (is(node, "ins") || is(node, "moveTo") || is(node, "smartTag") || is(node, "customXml") || is(node, "sdtContent"))
                inlineChildren(node, paragraph, part, range, out, plain, pages);
            else if ("http://schemas.openxmlformats.org/officeDocument/2006/math".equals(node.getNamespaceURI())) {
                if (!plain) {
                    notice("EQUATION_UNSUPPORTED", range, "数式オブジェクトの変換は未対応です。", false);
                    append(out, "（数式オブジェクトは未対応）");
                }
            }
        }
        private void field(Node node, String range, boolean plain) {
            switch (attr(node, "fldCharType")) {
                case "begin" -> { fieldResults.push(false); if (!plain) notice("FIELD_CACHED_RESULT", range, "フィールドは実行せず、保存済みの表示だけを保持しました。", true); }
                case "separate" -> { if (!fieldResults.isEmpty()) { fieldResults.pop(); fieldResults.push(true); } }
                case "end" -> { if (!fieldResults.isEmpty()) fieldResults.pop(); }
                default -> { }
            }
        }
        private boolean fieldVisible() { return !fieldResults.contains(false); }
        private String visibleText(Node root) {
            // Drawing text extraction has independent field state and does not resolve links/assets/notes.
            Deque<Boolean> saved = new ArrayDeque<>(fieldResults);
            fieldResults.clear();
            StringBuilder result = new StringBuilder();
            try { visibleParagraphs(root, result); }
            finally { fieldResults.clear(); fieldResults.addAll(saved); }
            return result.toString().strip();
        }
        private void visibleParagraphs(Node node, StringBuilder out) {
            if (node == null || omitted(node)) return;
            if (is(node, "p")) {
                StringBuilder line = new StringBuilder();
                inlineChildren(node, node, document.getPackagePart(), "drawing", line, true, false);
                if (!line.isEmpty()) { if (!out.isEmpty()) append(out, "\n"); append(out, line.toString()); }
            } else for (Node child : children(node)) visibleParagraphs(child, out);
        }
        private String table(Node table, PackagePart part, String range) {
            List<Node> rows = new ArrayList<>();
            collectRows(table, rows);
            List<List<String>> values = new ArrayList<>();
            int width = 0;
            Node grid = child(table, "tblGrid");
            if (grid != null) for (Node n : children(grid)) if (is(n, "gridCol")) width++;
            boolean header = false;
            for (Node row : rows) {
                if (on(child(child(row, "trPr"), "del"))) continue;
                if (values.isEmpty()) header = on(child(child(row, "trPr"), "tblHeader"));
                List<String> cells = new ArrayList<>();
                int before = boundedSpan(val(child(child(row, "trPr"), "gridBefore")), 0);
                checkTableWidth(Math.max(width, before), values.size() + 1, header);
                for (int i = 0; i < before; i++) cells.add("");
                List<Node> sourceCells = new ArrayList<>();
                collectCells(row, sourceCells);
                for (Node cell : sourceCells) {
                    Node props = child(cell, "tcPr");
                    if (child(props, "cellDel") != null) continue;
                    int span = boundedSpan(val(child(props, "gridSpan")), 1);
                    boolean continuation = child(props, "vMerge") != null && !"restart".equals(val(child(props, "vMerge")))
                            || child(props, "hMerge") != null && !"restart".equals(val(child(props, "hMerge")));
                    String text = continuation ? "" : cell(cell, part, range + "/row:" + (values.size() + 1) + "/column:" + (cells.size() + 1));
                    checkTableWidth(Math.max(width, cells.size() + (long) Math.max(1, span)), values.size() + 1, header);
                    cells.add(text);
                    for (int i = 1; i < span; i++) cells.add("");
                }
                int after = boundedSpan(val(child(child(row, "trPr"), "gridAfter")), 0);
                checkTableWidth(Math.max(width, cells.size() + (long) after), values.size() + 1, header);
                for (int i = 0; i < after; i++) cells.add("");
                values.add(cells);
                width = Math.max(width, cells.size());
            }
            if (width == 0 || values.isEmpty()) return "";
            tableCells += (long) width * (values.size() + (header ? 0 : 1));
            workspace.limits().checkTableCells(tableCells);
            StringBuilder out = new StringBuilder();
            int start = 0;
            if (header) { tableRow(out, values.getFirst(), width); start = 1; }
            else tableRow(out, List.of(), width);
            append(out, "|" + " --- |".repeat(width) + "\n");
            for (int i = start; i < values.size(); i++) tableRow(out, values.get(i), width);
            return out.toString().stripTrailing();
        }
        private void checkTableWidth(long width, int rows, boolean header) {
            workspace.limits().checkTableCells(tableCells + width * (rows + (header ? 0L : 1L)));
        }
        private int boundedSpan(String value, int fallback) {
            int number = integer(value, fallback);
            if (number < 0) throw ConversionWorkspace.limit("TABLE_CELLS_LIMIT", "表のセル範囲が有効ではありません。");
            workspace.limits().checkTableCells(number);
            return number;
        }
        private void collectRows(Node node, List<Node> rows) {
            for (Node n : children(node)) {
                if (omitted(n)) continue;
                if (is(n, "tr")) rows.add(n);
                else if (is(n, "ins") || is(n, "moveTo") || is(n, "sdtContent") || is(n, "sdt")) collectRows(n, rows);
            }
        }
        private void collectCells(Node node, List<Node> cells) {
            for (Node n : children(node)) {
                if (omitted(n)) continue;
                if (is(n, "tc")) cells.add(n);
                else if (is(n, "ins") || is(n, "moveTo") || is(n, "sdtContent") || is(n, "sdt")) collectCells(n, cells);
            }
        }
        private String cell(Node cell, PackagePart part, String range) {
            StringBuilder out = new StringBuilder();
            for (Node n : children(cell)) {
                if (omitted(n)) continue;
                String value = "";
                if (is(n, "p")) value = paragraph(n, part, range, true, false);
                else if (is(n, "tbl")) {
                    notice("NESTED_TABLE_UNSUPPORTED", range, "入れ子の表は文字だけを保持しました。", false);
                    value = Markdown.escape(visibleText(n));
                } else if (is(n, "ins") || is(n, "moveTo") || is(n, "sdtContent")) value = cell(n, part, range);
                else if (is(n, "sdt")) value = cell(child(n, "sdtContent"), part, range);
                if (!value.isBlank()) { if (!out.isEmpty()) append(out, "<br>"); append(out, value.replace("\n", "<br>")); }
            }
            return out.toString();
        }
        private void tableRow(StringBuilder out, List<String> cells, int width) {
            append(out, "|");
            for (int i = 0; i < width; i++) append(out, " " + (i < cells.size() ? cells.get(i) : "") + " |");
            append(out, "\n");
        }
        private void append(StringBuilder target, String value) {
            if ((long) target.length() + value.length() > workspace.limits().maxMarkdownBytes())
                throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdown のサイズが上限を超えています。");
            target.append(value);
        }
        private void notice(String code, String range, String message, boolean info) {
            if (!noticeKeys.add(code + "|" + range)) return;
            if (info) workspace.info(code, section, range, message);
            else workspace.warning(code, section, range, message);
        }
    }
}
