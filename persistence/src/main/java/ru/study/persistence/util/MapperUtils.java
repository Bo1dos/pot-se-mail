package ru.study.persistence.util;

import ru.study.core.model.EmailAddress;
import ru.study.core.exception.ValidationException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MapperUtils {
    private MapperUtils() {}

    // OffsetDateTime <-> Instant
    public static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    public static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }

    // CSV emails -> List<EmailAddress>
    public static List<EmailAddress> csvToEmails(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .map(s -> {
                         try { return new EmailAddress(s); }
                         catch (Exception ex) { throw new ValidationException("Invalid email in CSV: " + s); }
                     })
                     .collect(Collectors.toList());
    }

    public static String emailsToCsv(List<EmailAddress> emails) {
        if (emails == null || emails.isEmpty()) return "";
        return emails.stream()
                     .map(EmailAddress::value)
                     .collect(Collectors.joining(","));
    }

    public static Long nullSafeId(Number n) { return n == null ? null : n.longValue(); }

    /* --- Recipients parsing helpers --- */

    public static final class RecipientLists {
        public final List<EmailAddress> to;
        public final List<EmailAddress> cc;
        public RecipientLists(List<EmailAddress> to, List<EmailAddress> cc) {
            this.to = List.copyOf(to == null ? List.of() : to);
            this.cc = List.copyOf(cc == null ? List.of() : cc);
        }
    }

    /**
     * Parse recipients column. Supports:
     *  - simple CSV: "a@x,b@y" -> all go to 'to'
     *  - JSON-ish array of objects: [{"address":"a@x","type":"to"}, {"address":"c@d","type":"cc"}]
     *    parsing is tolerant and implemented without external libs (simple regex-based extraction).
     */
    public static RecipientLists parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) return new RecipientLists(List.of(), List.of());

        String trimmed = raw.trim();
        // If it looks like JSON array -> try structured parse
        if (trimmed.startsWith("[")) {
            List<EmailAddress> to = new ArrayList<>();
            List<EmailAddress> cc = new ArrayList<>();

            // pattern finds objects with "address":"..." and "type":"..."
            Pattern p = Pattern.compile("\\{[^}]*\"address\"\\s*:\\s*\"([^\"]+)\"[^}]*\"type\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}");
            Matcher m = p.matcher(trimmed);
            while (m.find()) {
                String addr = m.group(1).trim();
                String type = m.group(2).trim().toLowerCase();
                try {
                    EmailAddress ea = new EmailAddress(addr);
                    if ("cc".equals(type) || "bcc".equals(type)) cc.add(ea);
                    else to.add(ea);
                } catch (Exception ex) {
                    // skip invalid address
                    // TODO: подумать
                }
            }

            // fallback: try to extract any "address":"..." tokens if no type tokens matched
            if (to.isEmpty() && cc.isEmpty()) {
                Pattern p2 = Pattern.compile("\"address\"\\s*:\\s*\"([^\"]+)\"");
                Matcher m2 = p2.matcher(trimmed);
                while (m2.find()) {
                    try { to.add(new EmailAddress(m2.group(1).trim())); } catch (Exception ex) {}
                }
            }
            return new RecipientLists(to, cc);
        } else {
            // legacy CSV: treat all as 'to'
            try {
                List<EmailAddress> to = csvToEmails(trimmed);
                return new RecipientLists(to, List.of());
            } catch (Exception ex) {
                return new RecipientLists(List.of(), List.of());
            }
        }
    }
}
