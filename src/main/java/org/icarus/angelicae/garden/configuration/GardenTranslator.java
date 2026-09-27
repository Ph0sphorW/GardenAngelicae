package org.icarus.angelicae.garden.configuration;

import com.google.common.base.Preconditions;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslator;
import org.icarus.angelicae.garden.utility.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GardenTranslator extends MiniMessageTranslator {

    /** What every language file is called, the bundled ones and the ones in the locale directory alike. */
    private static final String FILE_SUFFIX = ".lang.properties";

    /**
     * The language the bundled translations are written in.
     * <p>
     * Used when neither the language file nor the bundled file of the configured language has an entry, so
     * that a message is never left out entirely.
     */
    private static final Locale BUNDLED_FALLBACK_LOCALE = Locale.US;

    /** The translations as they are on disk, one per language file. */
    private Map<Locale, Properties> translations;

    /**
     * The translations as they are bundled in the jar, one per language the plugin was asked for.
     * <p>
     * These are what a language file is completed from, so that a file which is behind the plugin, or was
     * edited by hand, does not leave a message without text.
     */
    private Map<Locale, Properties> bundledTranslations;

    /** Keys already reported as untranslated, so a gap is logged once instead of once per message. */
    private final Set<String> reportedMissingKeys = ConcurrentHashMap.newKeySet();

    private final File localeDirectory;

    public GardenTranslator(File localeDirectory) {
        this.localeDirectory = localeDirectory;
    }

    public void reload() {
        syncLangFiles();
        loadLangFiles();
        loadBundledTranslations();
    }

    private void syncLangFiles() {
        if (!localeDirectory.exists() && !localeDirectory.mkdirs()) { // shouldn't happen
            throw new IllegalStateException("Failed to create locale directory at " + localeDirectory.getAbsolutePath());
        }

        int found = 0;
        try {
            Enumeration<URL> resources = getClass().getClassLoader().getResources("locale");
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();

                try (FileSystem fs = "jar".equals(url.getProtocol()) ? FileSystems.newFileSystem(url.toURI(), Collections.emptyMap()) : null) {

                    Path internalLocaleDir = fs == null ? Paths.get(url.toURI()) : fs.getPath("locale");
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(internalLocaleDir, "*" + FILE_SUFFIX)) {
                        for (Path path : stream) {
                            mergeAndStoreProperties(path, readProperties(path));
                            found++;
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException("Failed to sync language files", e);
        }
        // A jar is not required to hold a directory entry for a folder it contains, and one that was
        // repackaged may not, in which case listing the folder finds nothing and every language file stays
        // exactly as it is. Saying so turns a silent gap into something readable in the log; messages are
        // still sent, see `loadBundledTranslations`, which reads its files by name instead of by listing.
        if (found == 0) {
            Logger.logWarn("No bundled translations were found, existing language files will not be completed.");
        }
        // special thanks to StackOverflow and other useful sites lol
    }

    private void mergeAndStoreProperties(Path internalFile, Properties internalProps) throws IOException {
        File externalFile = new File(localeDirectory, internalFile.getFileName().toString());

        Properties merged = new Properties();
        if (externalFile.exists()) {
            // What the file already has wins, a translation is never overwritten by the bundled default
            merged.putAll(readProperties(externalFile.toPath()));
            for (String key : internalProps.stringPropertyNames()) {
                merged.putIfAbsent(key, internalProps.getProperty(key));
            }

        } else {
            merged.putAll(internalProps);
            if (!externalFile.createNewFile()) {
                throw new IOException("Could not create file: " + externalFile);
            }
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(externalFile), StandardCharsets.UTF_8)) {
            storeWithoutComments(merged, writer);
        }
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private void storeWithoutComments(Properties props, Writer writer) throws IOException {
        List<String> keys = new ArrayList<>(props.stringPropertyNames());
        Collections.sort(keys);

        for (String key : keys) {
            writer.write(key + "=" + props.getProperty(key) + "\n");
        }
    }

    private void loadLangFiles() {
        if (!localeDirectory.isDirectory()) {
            throw new IllegalArgumentException("Locale directory is not a directory!");
        }
        // A plain map rather than an immutable one: two files can name the same language, by accident or on
        // a case sensitive file system, which is worth ignoring rather than refusing to start over.
        Map<Locale, Properties> loaded = new HashMap<>();
        File[] translationFiles = localeDirectory.listFiles(file -> file.getName().endsWith(FILE_SUFFIX));
        for (File translationFile : translationFiles == null ? new File[0] : translationFiles) {
            try (InputStream inputStream = new FileInputStream(translationFile)) {
                Properties translation = new Properties();
                translation.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                loaded.putIfAbsent(localeOf(translationFile.getName()), translation);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        this.translations = Map.copyOf(loaded);
        Preconditions.checkArgument(translations.containsKey(GardenConfig.instance().language()), "Unknown translation: " + GardenConfig.instance().language());
    }

    /**
     * Reads the languages a message may be answered from straight out of the jar.
     * <p>
     * By name rather than by listing the folder, so that it works on a jar whose folders can not be listed,
     * see the warning in {@link #syncLangFiles()} for why that happens.
     */
    private void loadBundledTranslations() {
        Map<Locale, Properties> bundled = new HashMap<>();
        // A set that tolerates repeats: the configured language is the fallback language in the common case
        Set<Locale> locales = new LinkedHashSet<>();
        locales.add(GardenConfig.instance().language());
        locales.add(BUNDLED_FALLBACK_LOCALE);
        for (Locale locale : locales) {
            Properties properties = readBundledProperties(locale);
            if (properties != null) {
                bundled.put(locale, properties);
            }
        }
        this.bundledTranslations = Map.copyOf(bundled);
    }

    private @Nullable Properties readBundledProperties(Locale locale) {
        String resource = "/locale/" + locale.toLanguageTag() + FILE_SUFFIX;
        try (InputStream inputStream = getClass().getResourceAsStream(resource)) {
            if (inputStream == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException e) {
            Logger.logWarn("Could not read the bundled translation %s".formatted(resource));
            return null;
        }
    }

    /**
     * The language a language file holds, taken from its name: {@code en-US.lang.properties} is the file for
     * {@link Locale#US}.
     */
    private static Locale localeOf(String fileName) {
        return Locale.forLanguageTag(fileName.substring(0, fileName.length() - FILE_SUFFIX.length()));
    }

    @Override
    public @NonNull Key name() {
        return Key.key("brewery:global_translator");
    }

    @Override
    public @Nullable String getMiniMessageString(@NonNull String key, @NonNull Locale locale) {
        String message = lookup(key, locale);
        if (message == null) {
            // Whoever asked still needs to be told something, the key itself, so the log entry here is what
            // points at the entry that has to be added to a language file.
            reportMissingKey(key, locale);
        }
        return message;
    }

    /**
     * Looks a key up in the languages the sender may be answered in, most specific first: their own language
     * when client side translations are on, then the server's language as the file has it, then the same
     * language as the jar has it, and finally the language the plugin is written in.
     * <p>
     * Looking at the bundled files as well as at the ones on disk is what makes a language file that predates
     * a message still usable: the message is shown in the bundled wording instead of not being shown at all.
     */
    private @Nullable String lookup(String key, Locale locale) {
        Locale configured = GardenConfig.instance().language();
        if (GardenConfig.instance().clientSidedTranslations()) {
            String clientMessage = property(translations, locale, key);
            if (clientMessage != null) {
                return clientMessage;
            }
        }
        String configuredMessage = property(translations, configured, key);
        if (configuredMessage != null) {
            return configuredMessage;
        }
        String bundledMessage = property(bundledTranslations, configured, key);
        return bundledMessage != null ? bundledMessage : property(bundledTranslations, BUNDLED_FALLBACK_LOCALE, key);
    }

    private static @Nullable String property(@Nullable Map<Locale, Properties> source, Locale locale, String key) {
        if (source == null) {
            return null;
        }
        Properties properties = source.get(locale);
        return properties == null ? null : properties.getProperty(key);
    }

    private void reportMissingKey(String key, Locale locale) {
        if (reportedMissingKeys.add(key)) {
            Logger.logWarn("No translation for '%s' in %s, showing the key itself".formatted(key, locale.toLanguageTag()));
        }
    }
}
