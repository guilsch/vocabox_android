package com.guilsch.vocabox;

import java.util.List;

/** Résultat d'une recherche : définitions + étymologie. */
public class WordEntry {

    /** Un groupe de définitions sous une même catégorie grammaticale (« Nom commun », « Verbe »…). */
    public static class Meaning {
        public final String category;
        public final List<String> definitions;

        public Meaning(String category, List<String> definitions) {
            this.category = category;
            this.definitions = definitions;
        }
    }

    public final String word;
    public final List<Meaning> meanings;
    public final String etymology;   // null si non renseignée
    public final String sourceUrl;

    public WordEntry(String word, List<Meaning> meanings, String etymology, String sourceUrl) {
        this.word = word;
        this.meanings = meanings;
        this.etymology = etymology;
        this.sourceUrl = sourceUrl;
    }
}
