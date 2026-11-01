package com.guilsch.vocabox;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Interroge l'API MediaWiki du Wiktionnaire français.
 * ⚠️ lookup() fait du réseau : à appeler depuis un thread secondaire, jamais depuis le thread principal.
 */
public class DictionaryRepository {

    // Renvoie le HTML rendu de la page du mot (ou un objet "error" si elle n'existe pas).
    private static final String API_URL =
            "https://fr.wiktionary.org/w/api.php"
                    + "?action=parse&prop=text&format=json&formatversion=2"
                    + "&redirects=1&disableeditsection=1&page=";

    // Wikimedia demande un User-Agent identifiable : remplace par le nom de ton app + un contact.
    private static final String USER_AGENT = "Vocabox/1.0 (contact: ton.email@exemple.fr)";

    /**
     * Cherche un mot.
     *
     * @return l'entrée trouvée, ou null si le mot n'existe pas en français
     * @throws IOException en cas de problème réseau ou de réponse illisible
     */
    public WordEntry lookup(String input) throws IOException {
        String word = input.trim();
        if (word.isEmpty()) return null;

        // Le Wiktionnaire distingue majuscules et minuscules : on essaie d'abord en minuscules
        // (« chat »), puis tel que saisi (« Paris »).
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(word.toLowerCase(Locale.FRENCH));
        candidates.add(word);

        for (String candidate : candidates) {
            String json = download(API_URL + URLEncoder.encode(candidate, "UTF-8"));
            try {
                JSONObject parse = new JSONObject(json).optJSONObject("parse");
                if (parse == null) continue; // page inexistante

                WordEntry entry = WiktionaryParser.parse(
                        parse.getString("title"),
                        parse.getString("text"));

                if (!entry.meanings.isEmpty() || entry.etymology != null) return entry;
            } catch (JSONException e) {
                throw new IOException("Réponse inattendue du serveur", e);
            }
        }
        return null;
    }

    private String download(String urlString) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Erreur HTTP " + code);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }
}
