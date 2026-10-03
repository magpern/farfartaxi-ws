package com.farfartaxi.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Push message bundle (UTF-8 properties, sv default / en), looked up per recipient locale. */
@Component
public class PushTexts {
    public static final String DEFAULT_LOCALE = "sv";
    public record Text(String title, String body) { }

    private final Map<String, Properties> bundles = new ConcurrentHashMap<>();

    public static boolean supported(String locale) {
        return "sv".equals(locale) || "en".equals(locale);
    }

    public Text resolve(String locale, String key, List<String> args) {
        Properties p = bundle(supported(locale) ? locale : DEFAULT_LOCALE);
        Properties fallback = bundle(DEFAULT_LOCALE);
        Locale l = Locale.forLanguageTag(supported(locale) ? locale : DEFAULT_LOCALE);
        Object[] a = args.toArray();
        String title = p.getProperty(key + ".title", fallback.getProperty(key + ".title", "{0}"));
        String body = p.getProperty(key + ".body", fallback.getProperty(key + ".body", ""));
        return new Text(new MessageFormat(title, l).format(a), new MessageFormat(body, l).format(a));
    }

    private Properties bundle(String locale) {
        return bundles.computeIfAbsent(locale, loc -> {
            Properties props = new Properties();
            try (InputStream in = PushTexts.class.getResourceAsStream("/push/messages_" + loc + ".properties")) {
                if (in == null) {
                    throw new IllegalStateException("Missing push bundle for " + loc);
                }
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read push bundle for " + loc, e);
            }
            return props;
        });
    }
}
