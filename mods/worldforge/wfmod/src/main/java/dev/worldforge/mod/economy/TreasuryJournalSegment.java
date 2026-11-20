package dev.worldforge.mod.economy;

import dev.worldforge.mod.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, checksummed archive unit for the treasury's append-only audit history.
 *
 * A segment is deliberately independent from the live account snapshot: archiving history
 * must never rewrite balances or discard idempotency evidence. The caller is responsible
 * for publishing a segment atomically before advancing its snapshot/archive checkpoint.
 */
public final class TreasuryJournalSegment {
    public static final String FORMAT = "wf-treasury-segment/1";
    public static final int MAX_ENTRIES = 65_536;

    private TreasuryJournalSegment() { }

    public record Decoded(long firstSequence, long lastSequence, List<TreasuryLedger.Entry> entries,
                          String checksum) {
        public Decoded {
            entries = List.copyOf(entries);
        }
    }

    public static String encode(List<TreasuryLedger.Entry> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES)
            throw new IllegalArgumentException("Treasury segment entry count must be 1.." + MAX_ENTRIES);
        long previous = 0;
        for (TreasuryLedger.Entry entry : entries) {
            if (entry == null || entry.sequence() <= previous)
                throw new IllegalArgumentException("Treasury segment entries must have strictly increasing sequences");
            previous = entry.sequence();
        }
        long first = entries.get(0).sequence();
        long last = entries.get(entries.size() - 1).sequence();
        Map<String, Object> payload = payload(first, last, entries);
        String checksum = sha256(Json.write(payload));
        Map<String, Object> envelope = new LinkedHashMap<>(payload);
        envelope.put("sha256", checksum);
        return Json.write(envelope);
    }

    public static Decoded decode(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> root))
            throw new IllegalArgumentException("Treasury segment root must be an object");
        if (!FORMAT.equals(root.get("format")))
            throw new IllegalArgumentException("Unsupported treasury segment format");
        long first = requiredLong(root.get("firstSequence"), "first sequence");
        long last = requiredLong(root.get("lastSequence"), "last sequence");
        if (!(root.get("entries") instanceof List<?> rows) || rows.isEmpty() || rows.size() > MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid treasury segment entry count");
        String expectedChecksum = requiredString(root.get("sha256"), "segment checksum");

        List<TreasuryLedger.Entry> entries = new ArrayList<>(rows.size());
        long previous = 0;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> m))
                throw new IllegalArgumentException("Invalid treasury segment entry");
            long sequence = requiredLong(m.get("seq"), "entry sequence");
            String tx = requiredString(m.get("tx"), "transaction id");
            String account = requiredString(m.get("account"), "account id");
            String other = optionalString(m.get("other"));
            String currency = requiredString(m.get("currency"), "currency");
            double amount = requiredNumber(m.get("amount"), "amount");
            Object creditValue = m.get("credit");
            if (!(creditValue instanceof Boolean credit))
                throw new IllegalArgumentException("Invalid treasury segment credit flag");
            TreasuryLedger.Kind kind;
            try {
                kind = TreasuryLedger.Kind.valueOf(requiredString(m.get("kind"), "entry kind"));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Invalid treasury segment entry kind", ex);
            }
            String category = optionalString(m.get("category"));
            String reference = optionalString(m.get("reference"));
            long minute = requiredLong(m.get("minute"), "world minute");
            double balance = requiredNumber(m.get("balance"), "balance after");
            String memo = optionalString(m.get("memo"));
            if (sequence <= previous || sequence < 1 || amount <= 0 || balance < 0)
                throw new IllegalArgumentException("Invalid treasury segment ordering or values at " + sequence);
            entries.add(new TreasuryLedger.Entry(sequence, tx, account, other, currency, amount,
                    credit, kind, category, reference, minute, balance, memo));
            previous = sequence;
        }
        if (entries.get(0).sequence() != first || previous != last)
            throw new IllegalArgumentException("Treasury segment sequence bounds do not match its entries");

        String actualChecksum = sha256(Json.write(payload(first, last, entries)));
        if (!MessageDigest.isEqual(expectedChecksum.getBytes(StandardCharsets.US_ASCII),
                actualChecksum.getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("Treasury segment checksum mismatch");
        return new Decoded(first, last, entries, actualChecksum);
    }

    private static Map<String, Object> payload(long first, long last, List<TreasuryLedger.Entry> entries) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("firstSequence", first);
        root.put("lastSequence", last);
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (TreasuryLedger.Entry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", entry.sequence());
            row.put("tx", entry.transactionId());
            row.put("account", entry.accountId());
            row.put("other", entry.counterpartyId());
            row.put("currency", entry.currency());
            row.put("amount", entry.amount());
            row.put("credit", entry.credit());
            row.put("kind", entry.kind().name());
            row.put("category", entry.category());
            row.put("reference", entry.reference());
            row.put("minute", entry.worldMinute());
            row.put("balance", entry.balanceAfter());
            row.put("memo", entry.memo());
            rows.add(row);
        }
        root.put("entries", rows);
        return root;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 256)
            throw new IllegalArgumentException("Invalid treasury segment " + field);
        return text.trim();
    }

    private static String optionalString(Object value) {
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > 256)
            throw new IllegalArgumentException("Invalid treasury segment string");
        return text;
    }

    private static long requiredLong(Object value, String field) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 0
                || n.doubleValue() > Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid treasury segment " + field);
        return n.longValue();
    }

    private static double requiredNumber(Object value, String field) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
            throw new IllegalArgumentException("Invalid treasury segment " + field);
        return n.doubleValue();
    }
}
