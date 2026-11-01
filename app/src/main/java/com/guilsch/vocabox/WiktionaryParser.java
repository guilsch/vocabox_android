package com.guilsch.vocabox;

import android.text.TextUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Extrait la section « Français » d'une page du Wiktionnaire :
 *  - l'étymologie (section « Étymologie »)
 *  - les définitions (les listes <ol> sous « Nom commun », « Verbe », « Adjectif »…)
 *
 * ⚠️ Cette logique dépend du balisage HTML du Wiktionnaire. S'il évolue, c'est ici
 * qu'il faudra ajuster (les deux variantes de balisage des titres sont gérées).
 */
public final class WiktionaryParser {

    private static final Pattern HEADING_TAG = Pattern.compile("h[1-6]");

    private WiktionaryParser() { }

    public static WordEntry parse(String title, String html) {
        Document doc = Jsoup.parse(html);
        doc.select(".mw-editsection, sup.reference, style, script").remove();

        List<WordEntry.Meaning> meanings = new ArrayList<>();
        List<String> etymologies = new ArrayList<>();

        // L'ancre #fr marque le début de la section « Français » de la page.
        Element frenchAnchor = doc.getElementById("fr");
        if (frenchAnchor == null) frenchAnchor = doc.getElementById("Français");

        if (frenchAnchor != null) {
            Element node = headingBlock(frenchAnchor).nextElementSibling();
            String sectionTitle = "";
            boolean inEtymology = false;

            while (node != null) {
                Element heading = headingOf(node);

                if (heading != null) {
                    // Un titre de niveau 2 = début d'une autre langue : on s'arrête.
                    int level = Character.digit(heading.tagName().charAt(1), 10);
                    if (level <= 2) break;
                    sectionTitle = heading.text().trim();
                    inEtymology = sectionTitle.toLowerCase(Locale.FRENCH).startsWith("étymologie");

                } else if (inEtymology) {
                    String tag = node.tagName();
                    if (tag.equals("dl") || tag.equals("p")) {
                        String text = node.text().trim();
                        boolean isPlaceholder =
                                text.toLowerCase(Locale.FRENCH).contains("étymologie manquante");
                        if (!text.isEmpty() && !isPlaceholder) etymologies.add(text);
                    }

                } else if (node.tagName().equals("ol") && !node.hasClass("references")) {
                    List<String> definitions = new ArrayList<>();
                    for (Element li : node.children()) {
                        if (!li.tagName().equals("li")) continue;
                        // On retire les exemples imbriqués pour ne garder que la définition.
                        Element copy = li.clone();
                        copy.select("ul, ol, dl").remove();
                        String text = copy.text().trim();
                        if (!text.isEmpty()) definitions.add(text);
                    }
                    if (!definitions.isEmpty()) {
                        meanings.add(new WordEntry.Meaning(sectionTitle, definitions));
                    }
                }

                node = node.nextElementSibling();
            }
        }

        String etymology = etymologies.isEmpty() ? null : TextUtils.join("\n\n", etymologies);
        return new WordEntry(title, meanings, etymology, wiktionaryUrl(title));
    }

    /** Renvoie la balise h1–h6 si l'élément est un titre (ancien ou nouveau balisage MediaWiki). */
    private static Element headingOf(Element el) {
        if (HEADING_TAG.matcher(el.tagName()).matches()) return el;
        if (el.hasClass("mw-heading")) return el.selectFirst("h1, h2, h3, h4, h5, h6");
        return null;
    }

    /** Remonte de l'ancre jusqu'au bloc de titre, pour pouvoir parcourir ses frères suivants. */
    private static Element headingBlock(Element el) {
        Element h = el;
        while (!HEADING_TAG.matcher(h.tagName()).matches()) {
            h = h.parent();
            if (h == null) return el;
        }
        Element parent = h.parent();
        return (parent != null && parent.hasClass("mw-heading")) ? parent : h;
    }

    private static String wiktionaryUrl(String title) {
        try {
            return "https://fr.wiktionary.org/wiki/"
                    + URLEncoder.encode(title, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return "https://fr.wiktionary.org/";
        }
    }
}
